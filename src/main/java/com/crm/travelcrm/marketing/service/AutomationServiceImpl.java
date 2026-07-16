package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.dto.AutomationResponse;
import com.crm.travelcrm.marketing.dto.SendTestRequest;
import com.crm.travelcrm.marketing.dto.UpcomingCelebrationResponse;
import com.crm.travelcrm.marketing.dto.UpdateAutomationRequest;
import com.crm.travelcrm.marketing.entity.AutomationTrigger;
import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.repository.AutomationTriggerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AutomationServiceImpl implements AutomationService {

    private final AutomationTriggerRepository triggerRepository;
    private final CustomerRepository customerRepository;
    private final MessageDispatcher dispatcher;

    @Override
    @Transactional
    public ApiResponse<List<AutomationResponse>> list() {
        Long tenantId = requireTenantId();
        Map<AutomationType, Long> counts = countByType(computeUpcoming(tenantId, 30));
        List<AutomationResponse> out = new ArrayList<>();
        for (AutomationType type : AutomationType.values()) {
            AutomationTrigger t = getOrProvision(tenantId, type);
            out.add(toResponse(t, counts.getOrDefault(type, 0L)));
        }
        return ApiResponse.success("Automations fetched successfully", out);
    }

    @Override
    @Transactional
    public ApiResponse<AutomationResponse> get(AutomationType type) {
        Long tenantId = requireTenantId();
        AutomationTrigger t = getOrProvision(tenantId, type);
        return ApiResponse.success("Automation fetched successfully", toResponse(t, upcomingCount(tenantId, type)));
    }

    @Override
    @Transactional
    public ApiResponse<AutomationResponse> update(AutomationType type, UpdateAutomationRequest request) {
        Long tenantId = requireTenantId();
        AutomationTrigger t = getOrProvision(tenantId, type);
        t.setEnabled(request.enabled());
        t.setChannel(request.channel());
        t.setDaysBefore(request.daysBefore());
        t.setSendTime(request.sendTime());
        t.setSubject(trimOrNull(request.subject()));
        t.setBody(request.body().trim());
        t.setTemplateName(trimOrNull(request.templateName()));
        AutomationTrigger saved = triggerRepository.save(t);
        return ApiResponse.success("Automation updated successfully", toResponse(saved, upcomingCount(tenantId, type)));
    }

    @Override
    @Transactional
    public ApiResponse<Void> test(AutomationType type, SendTestRequest request) {
        Long tenantId = requireTenantId();
        AutomationTrigger t = getOrProvision(tenantId, type);
        Customer sample = sampleCustomer(t.getChannel(), request.to().trim());
        MessageDispatcher.DispatchResult res = dispatcher.deliver(
                tenantId, t.getChannel(), sample, t.getSubject(), t.getBody(), t.getTemplateName());
        if (!res.sent()) {
            throw new BusinessException("Test send failed: " + (res.error() == null ? "unknown error" : res.error()), HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success("Test message sent to " + request.to().trim());
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<List<UpcomingCelebrationResponse>> upcoming(int days) {
        Long tenantId = requireTenantId();
        List<UpcomingCelebrationResponse> out = computeUpcoming(tenantId, days).stream().limit(100).toList();
        return ApiResponse.success("Upcoming celebrations", out);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private AutomationTrigger getOrProvision(Long tenantId, AutomationType type) {
        return triggerRepository.findByTenantIdAndTriggerType(tenantId, type)
                .orElseGet(() -> triggerRepository.save(AutomationTrigger.builder()
                        .triggerType(type)
                        .enabled(false)
                        .channel(MarketingChannel.WHATSAPP)
                        .daysBefore(0)
                        .sendTime(LocalTime.of(9, 0))
                        .build()));
    }

    private AutomationResponse toResponse(AutomationTrigger t, long upcomingCount) {
        return new AutomationResponse(
                t.getTriggerType(), t.isEnabled(), t.getChannel(), t.getDaysBefore(), t.getSendTime(),
                t.getSubject(), t.getBody(), t.getTemplateName(), t.getLastRunOn(), t.getTotalSent(), upcomingCount);
    }

    private long upcomingCount(Long tenantId, AutomationType type) {
        return computeUpcoming(tenantId, 30).stream().filter(u -> u.triggerType() == type).count();
    }

    private Map<AutomationType, Long> countByType(List<UpcomingCelebrationResponse> upcoming) {
        Map<AutomationType, Long> m = new EnumMap<>(AutomationType.class);
        for (UpcomingCelebrationResponse u : upcoming) {
            m.merge(u.triggerType(), 1L, Long::sum);
        }
        return m;
    }

    private List<UpcomingCelebrationResponse> computeUpcoming(Long tenantId, int days) {
        Map<AutomationType, MarketingChannel> channelByType = new EnumMap<>(AutomationType.class);
        triggerRepository.findByTenantId(tenantId).forEach(t -> channelByType.put(t.getTriggerType(), t.getChannel()));

        List<Customer> customers = customerRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);
        LocalDate today = LocalDate.now();
        List<UpcomingCelebrationResponse> out = new ArrayList<>();
        for (Customer c : customers) {
            addUpcoming(out, c, c.getBirthday(), AutomationType.BIRTHDAY, today, days,
                    channelByType.getOrDefault(AutomationType.BIRTHDAY, MarketingChannel.WHATSAPP));
            addUpcoming(out, c, c.getAnniversary(), AutomationType.ANNIVERSARY, today, days,
                    channelByType.getOrDefault(AutomationType.ANNIVERSARY, MarketingChannel.WHATSAPP));
        }
        out.sort(Comparator.comparingLong(UpcomingCelebrationResponse::daysAway));
        return out;
    }

    private void addUpcoming(List<UpcomingCelebrationResponse> out, Customer c, LocalDate occasion,
                             AutomationType type, LocalDate today, int days, MarketingChannel channel) {
        if (occasion == null) return;
        LocalDate next = occasion.withYear(today.getYear());   // withYear adjusts Feb 29 → Feb 28 in non-leap years
        if (next.isBefore(today)) next = next.plusYears(1);
        long daysAway = ChronoUnit.DAYS.between(today, next);
        if (daysAway < 0 || daysAway > days) return;
        boolean reachable = channel == MarketingChannel.EMAIL
                ? StringUtils.hasText(c.getEmail())
                : StringUtils.hasText(c.getPhone());
        out.add(new UpcomingCelebrationResponse(c.getPublicId(), c.getName(), next, daysAway, type, channel, reachable));
    }

    private Customer sampleCustomer(MarketingChannel channel, String to) {
        Customer sample = Customer.builder()
                .name("Sample Customer").city("Mumbai").state("Maharashtra")
                .tier(LoyaltyTier.GOLD).customerCode("CUS10001").build();
        if (channel == MarketingChannel.EMAIL) sample.setEmail(to);
        else sample.setPhone(to);
        return sample;
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