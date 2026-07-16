package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.ratelimit.RateLimitService;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.entity.Campaign;
import com.crm.travelcrm.marketing.entity.CampaignRecipient;
import com.crm.travelcrm.marketing.entity.Segment;
import com.crm.travelcrm.marketing.enums.CampaignAudienceType;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;
import com.crm.travelcrm.marketing.repository.CampaignRecipientRepository;
import com.crm.travelcrm.marketing.repository.CampaignRepository;
import com.crm.travelcrm.marketing.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The engine that turns a due campaign into per-recipient sends. Exposes small transactional
 * units ({@link #dueCampaignIds}, {@link #dispatchCampaign}) that the scheduler orchestrates
 * cross-bean, so each campaign's dispatch is its own transaction (no self-invocation).
 *
 * <p>Resumable + throttled: a SCHEDULED campaign is materialised into PENDING recipients, sent in
 * bounded, rate-limited batches; when a per-minute window is exhausted the rest stay PENDING and
 * resume on the next scheduler tick. Reachability (email/phone present) is decided at
 * materialisation — unreachable customers become SKIPPED, never FAILED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignDispatchService {

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final SegmentRepository segmentRepository;
    private final SegmentAudienceResolver audienceResolver;
    private final MessageDispatcher dispatcher;
    private final CustomerRepository customerRepository;
    private final RateLimitService rateLimitService;

    @Value("${app.marketing.rate.per-minute:200}")
    private int ratePerMinute;

    @Value("${app.marketing.batch-size:300}")
    private int batchSize;

    /** Ids of campaigns needing work this tick: SCHEDULED whose time has come + in-flight SENDING. */
    @Transactional(readOnly = true)
    public List<Long> dueCampaignIds(Long tenantId) {
        List<Long> ids = new ArrayList<>();
        campaignRepository.findByTenantIdAndStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
                tenantId, CampaignStatus.SCHEDULED, LocalDateTime.now()).forEach(c -> ids.add(c.getId()));
        campaignRepository.findByTenantIdAndStatusAndDeletedAtIsNull(tenantId, CampaignStatus.SENDING)
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    @Transactional
    public void dispatchCampaign(Long campaignId, Long tenantId) {
        Campaign c = campaignRepository.findById(campaignId)
                .filter(x -> tenantId.equals(x.getTenantId()) && !x.isDeleted())
                .orElse(null);
        if (c == null) return;

        if (c.getStatus() == CampaignStatus.SCHEDULED) {
            materialise(c, tenantId);
            c.setStatus(CampaignStatus.SENDING);
            campaignRepository.save(c);
        }
        if (c.getStatus() != CampaignStatus.SENDING) return;

        sendBatch(c, tenantId);

        boolean pendingLeft = recipientRepository.existsByCampaignIdAndTenantIdAndStatus(
                c.getId(), tenantId, RecipientStatus.PENDING);
        if (!pendingLeft) {
            c.setStatus(CampaignStatus.SENT);
            c.setSentAt(LocalDateTime.now());
            campaignRepository.save(c);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private void materialise(Campaign c, Long tenantId) {
        Specification<Customer> spec;
        if (c.getAudienceType() == CampaignAudienceType.ALL_CUSTOMERS) {
            spec = audienceResolver.forAllCustomers(tenantId);
        } else {
            Segment seg = c.getSegmentId() == null ? null
                    : segmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(c.getSegmentId(), tenantId).orElse(null);
            spec = seg == null ? null : audienceResolver.forSegment(tenantId, seg);
        }
        List<Customer> audience = spec == null ? List.of() : audienceResolver.list(spec);

        List<CampaignRecipient> rows = new ArrayList<>(audience.size());
        for (Customer cust : audience) {
            boolean reachable = c.getChannel() == MarketingChannel.EMAIL
                    ? StringUtils.hasText(cust.getEmail())
                    : StringUtils.hasText(cust.getPhone());
            rows.add(CampaignRecipient.builder()
                    .campaignId(c.getId())
                    .customerId(cust.getId())
                    .customerNameSnapshot(cust.getName())
                    .destination(c.getChannel() == MarketingChannel.EMAIL ? cust.getEmail() : cust.getPhone())
                    .channel(c.getChannel())
                    .status(reachable ? RecipientStatus.PENDING : RecipientStatus.SKIPPED)
                    .errorMsg(reachable ? null : (c.getChannel() == MarketingChannel.EMAIL ? "No email address" : "No phone number"))
                    .build());
        }
        recipientRepository.saveAll(rows);
        c.setTotalRecipients(rows.size());
    }

    private void sendBatch(Campaign c, Long tenantId) {
        List<CampaignRecipient> batch = recipientRepository.findByCampaignIdAndTenantIdAndStatusOrderByIdAsc(
                c.getId(), tenantId, RecipientStatus.PENDING, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        Set<Long> ids = batch.stream().map(CampaignRecipient::getCustomerId).collect(Collectors.toSet());
        Map<Long, Customer> byId = customerRepository.findByIdInAndTenantIdAndDeletedAtIsNull(ids, tenantId)
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));

        String rateKey = "mkt:campaign:" + tenantId + ":" + c.getChannel();
        int sent = 0, failed = 0;
        for (CampaignRecipient r : batch) {
            if (!rateLimitService.isAllowed(rateKey, ratePerMinute, Duration.ofMinutes(1))) {
                break;   // window exhausted — leave the rest PENDING for the next tick
            }
            Customer cust = byId.get(r.getCustomerId());
            if (cust == null) {
                r.setStatus(RecipientStatus.SKIPPED);
                r.setErrorMsg("Customer no longer exists");
                continue;
            }
            MessageDispatcher.DispatchResult res = dispatcher.deliver(
                    tenantId, c.getChannel(), cust, c.getSubject(), c.getBody(), c.getTemplateName());
            r.setStatus(res.status());
            r.setErrorMsg(res.error());
            if (res.status() == RecipientStatus.SENT) {
                r.setSentAt(LocalDateTime.now());
                sent++;
            } else if (res.status() == RecipientStatus.FAILED) {
                failed++;
            }
        }
        recipientRepository.saveAll(batch);
        c.setSentCount(c.getSentCount() + sent);
        c.setFailedCount(c.getFailedCount() + failed);
        campaignRepository.save(c);
    }
}