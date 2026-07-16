package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.dto.*;
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
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private static final Set<CampaignStatus> EDITABLE = Set.of(CampaignStatus.DRAFT, CampaignStatus.SCHEDULED);
    private static final Set<CampaignStatus> DELETABLE = Set.of(CampaignStatus.DRAFT, CampaignStatus.FAILED, CampaignStatus.CANCELLED);

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final SegmentRepository segmentRepository;
    private final CustomerRepository customerRepository;
    private final MessageDispatcher dispatcher;
    private final SubAgentScope subAgentScope;
    private final CurrentUserProvider currentUser;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CampaignResponse> list(int page, int size, String sortBy, String sortDir,
                                                   String search, CampaignStatus status, MarketingChannel channel) {
        Long tenantId = requireTenantId();
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Campaign> p = campaignRepository.findAll(listSpec(tenantId, search, status, channel), pageable);
        List<CampaignResponse> content = p.getContent().stream().map(this::toResponse).toList();
        return PagedApiResponse.of("Campaigns fetched successfully", content, PaginationMeta.from(p, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<CampaignSummaryResponse> summary() {
        Long tenantId = requireTenantId();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        CampaignSummaryResponse s = new CampaignSummaryResponse(
                campaignRepository.countByTenantIdAndDeletedAtIsNull(tenantId),
                campaignRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, CampaignStatus.DRAFT),
                campaignRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, CampaignStatus.SCHEDULED),
                campaignRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, CampaignStatus.SENT),
                campaignRepository.sumSentCountSince(tenantId, monthStart),
                customerRepository.countByTenantIdAndDeletedAtIsNull(tenantId));   // phone is mandatory → all reachable
        return ApiResponse.success("Summary computed", s);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<CampaignResponse> get(UUID publicId) {
        return ApiResponse.success("Campaign fetched successfully", toResponse(requireVisible(publicId)));
    }

    @Override
    @Transactional
    public ApiResponse<CampaignResponse> create(CreateCampaignRequest request) {
        Long tenantId = requireTenantId();
        Segment seg = resolveSegment(request.audienceType(), request.segmentPublicId(), tenantId);
        CampaignStatus status = isFuture(request.scheduledAt()) ? CampaignStatus.SCHEDULED : CampaignStatus.DRAFT;

        Campaign c = Campaign.builder()
                .name(request.name().trim())
                .channel(request.channel())
                .status(status)
                .audienceType(request.audienceType())
                .segmentId(seg != null ? seg.getId() : null)
                .segmentPublicId(seg != null ? seg.getPublicId() : null)
                .segmentNameSnapshot(seg != null ? seg.getName() : null)
                .subject(trimOrNull(request.subject()))
                .body(request.body().trim())
                .templateName(trimOrNull(request.templateName()))
                .scheduledAt(request.scheduledAt())
                .build();
        Campaign saved = campaignRepository.save(c);
        return ApiResponse.success("Campaign created successfully", toResponse(saved), 201);
    }

    @Override
    @Transactional
    public ApiResponse<CampaignResponse> update(UUID publicId, UpdateCampaignRequest request) {
        Long tenantId = requireTenantId();
        Campaign c = requireVisible(publicId);
        if (!EDITABLE.contains(c.getStatus())) {
            throw new BusinessException("Only draft or scheduled campaigns can be edited.", HttpStatus.CONFLICT);
        }
        Segment seg = resolveSegment(request.audienceType(), request.segmentPublicId(), tenantId);
        c.setName(request.name().trim());
        c.setChannel(request.channel());
        c.setAudienceType(request.audienceType());
        c.setSegmentId(seg != null ? seg.getId() : null);
        c.setSegmentPublicId(seg != null ? seg.getPublicId() : null);
        c.setSegmentNameSnapshot(seg != null ? seg.getName() : null);
        c.setSubject(trimOrNull(request.subject()));
        c.setBody(request.body().trim());
        c.setTemplateName(trimOrNull(request.templateName()));
        c.setScheduledAt(request.scheduledAt());
        c.setStatus(isFuture(request.scheduledAt()) ? CampaignStatus.SCHEDULED : CampaignStatus.DRAFT);
        return ApiResponse.success("Campaign updated successfully", toResponse(campaignRepository.save(c)));
    }

    @Override
    @Transactional
    public ApiResponse<Void> delete(UUID publicId) {
        Campaign c = requireVisible(publicId);
        if (!DELETABLE.contains(c.getStatus())) {
            throw new BusinessException("A queued or sending campaign cannot be deleted. Cancel it first.", HttpStatus.CONFLICT);
        }
        c.softDelete(currentUser.currentUsernameOrSystem());
        campaignRepository.save(c);
        return ApiResponse.success("Campaign deleted successfully");
    }

    @Override
    @Transactional
    public ApiResponse<CampaignResponse> send(UUID publicId) {
        Campaign c = requireVisible(publicId);
        if (!EDITABLE.contains(c.getStatus())) {
            throw new BusinessException("This campaign has already been sent or is in progress.", HttpStatus.CONFLICT);
        }
        // Enqueue for immediate dispatch: the CampaignDispatchScheduler picks up due SCHEDULED campaigns.
        c.setScheduledAt(LocalDateTime.now());
        c.setStatus(CampaignStatus.SCHEDULED);
        return ApiResponse.success("Campaign queued for sending", toResponse(campaignRepository.save(c)));
    }

    @Override
    @Transactional
    public ApiResponse<CampaignResponse> cancel(UUID publicId) {
        Campaign c = requireVisible(publicId);
        if (c.getStatus() != CampaignStatus.SCHEDULED) {
            throw new BusinessException("Only a scheduled campaign can be cancelled.", HttpStatus.CONFLICT);
        }
        c.setStatus(CampaignStatus.CANCELLED);
        return ApiResponse.success("Campaign cancelled", toResponse(campaignRepository.save(c)));
    }

    @Override
    @Transactional
    public ApiResponse<Void> test(UUID publicId, SendTestRequest request) {
        Long tenantId = requireTenantId();
        Campaign c = requireVisible(publicId);
        Customer sample = sampleCustomer(c.getChannel(), request.to().trim());
        MessageDispatcher.DispatchResult res = dispatcher.deliver(
                tenantId, c.getChannel(), sample, c.getSubject(), c.getBody(), c.getTemplateName());
        if (!res.sent()) {
            throw new BusinessException("Test send failed: " + (res.error() == null ? "unknown error" : res.error()), HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success("Test message sent to " + request.to().trim());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CampaignRecipientResponse> recipients(UUID publicId, int page, int size, RecipientStatus status) {
        Long tenantId = requireTenantId();
        Campaign c = requireVisible(publicId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<CampaignRecipient> p = (status == null)
                ? recipientRepository.findByCampaignIdAndTenantId(c.getId(), tenantId, pageable)
                : recipientRepository.findByCampaignIdAndTenantIdAndStatus(c.getId(), tenantId, status, pageable);
        List<CampaignRecipientResponse> content = p.getContent().stream()
                .map(r -> new CampaignRecipientResponse(r.getPublicId(), r.getCustomerNameSnapshot(), r.getDestination(),
                        r.getChannel(), r.getStatus(), r.getErrorMsg(), r.getSentAt()))
                .toList();
        return PagedApiResponse.of("Recipients fetched successfully", content, PaginationMeta.from(p));
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private CampaignResponse toResponse(Campaign c) {
        return new CampaignResponse(
                c.getPublicId(), c.getName(), c.getChannel(), c.getStatus(), c.getAudienceType(),
                c.getSegmentPublicId(), c.getSegmentNameSnapshot(), c.getSubject(), c.getBody(), c.getTemplateName(),
                c.getScheduledAt(), c.getSentAt(), c.getTotalRecipients(), c.getSentCount(), c.getFailedCount(),
                c.getCreatedBy(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private Segment resolveSegment(CampaignAudienceType audienceType, UUID segmentPublicId, Long tenantId) {
        if (audienceType != CampaignAudienceType.SEGMENT) return null;
        if (segmentPublicId == null) {
            throw new BusinessException("Choose a segment for a segment-targeted campaign.");
        }
        Segment seg = segmentRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(segmentPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found: " + segmentPublicId));
        subAgentScope.assertVisible(seg, segmentPublicId);
        return seg;
    }

    private Customer sampleCustomer(MarketingChannel channel, String to) {
        Customer sample = Customer.builder()
                .name("Sample Customer").city("Mumbai").state("Maharashtra")
                .tier(LoyaltyTier.GOLD).customerCode("CUS10001").build();
        if (channel == MarketingChannel.EMAIL) sample.setEmail(to);
        else sample.setPhone(to);
        return sample;
    }

    private Specification<Campaign> listSpec(Long tenantId, String search, CampaignStatus status, MarketingChannel channel) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            ps.add(cb.isNull(root.get("deletedAt")));
            Long owner = subAgentScope.ownerFilter();
            if (owner != null) ps.add(cb.equal(root.get("ownerUserId"), owner));
            if (StringUtils.hasText(search)) ps.add(cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%"));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (channel != null) ps.add(cb.equal(root.get("channel"), channel));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Campaign requireVisible(UUID publicId) {
        Campaign c = campaignRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found: " + publicId));
        subAgentScope.assertVisible(c, publicId);
        return c;
    }

    private boolean isFuture(LocalDateTime t) {
        return t != null && t.isAfter(LocalDateTime.now());
    }

    private Long requireTenantId() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new IllegalStateException("TenantContext is empty — no tenant in scope.");
        return t;
    }

    private String trimOrNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}