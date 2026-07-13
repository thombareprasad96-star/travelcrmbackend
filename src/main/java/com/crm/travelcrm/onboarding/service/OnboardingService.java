package com.crm.travelcrm.onboarding.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.master.hotel.HotelRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.onboarding.dto.OnboardingChecklistResponse;
import com.crm.travelcrm.onboarding.dto.OnboardingStepDto;
import com.crm.travelcrm.onboarding.entity.OnboardingStepMarker;
import com.crm.travelcrm.onboarding.model.OnboardingStep;
import com.crm.travelcrm.onboarding.repository.OnboardingStepMarkerRepository;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guided onboarding activation checklist. Each step's "done" flag is computed <b>live</b> per request
 * from existing tenant data (no per-step persistence); only manual dismissals and a once-ever
 * celebration sentinel are stored in {@code onboarding_step_markers}. When every step becomes
 * complete (done OR dismissed) the tenant's admins/managers get a one-time congratulatory
 * {@link NotifyEvent}.
 *
 * <p>The read method is {@code @Transactional} (writable, not read-only) for two reasons: the
 * Hibernate {@code tenantFilter} must be engaged for the tenant-scoped signal queries, and the
 * completion path conditionally writes the celebration marker. That write is idempotent — guarded by
 * an exists-check with the {@code (tenant_id, step_key)} unique constraint as the race backstop.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    /** Reserved {@code step_key} whose row existence is the once-ever celebration guard. Matches no enum. */
    private static final String CELEBRATION_KEY = "__CELEBRATION_FIRED__";

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final HotelRepository hotelRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final LeadRepository leadRepository;
    private final QuotationRepository quotationRepository;
    private final BookingRepository bookingRepository;
    private final OnboardingStepMarkerRepository markerRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OnboardingChecklistResponse getChecklist() {
        Long tenantId = requireTenant();

        // step keys the tenant has actively dismissed (the sentinel is in here but matches no enum → ignored)
        Set<String> dismissed = markerRepository.findByTenantId(tenantId).stream()
                .filter(OnboardingStepMarker::isDismissed)
                .map(OnboardingStepMarker::getStepKey)
                .collect(Collectors.toSet());

        Map<OnboardingStep, Boolean> done = computeDoneFlags(tenantId);

        List<OnboardingStepDto> steps = new ArrayList<>(OnboardingStep.values().length);
        int completed = 0;
        for (OnboardingStep step : OnboardingStep.values()) {
            boolean isDone = done.getOrDefault(step, false);
            boolean isDismissed = dismissed.contains(step.getKey());
            boolean isComplete = isDone || isDismissed;
            if (isComplete) {
                completed++;
            }
            steps.add(new OnboardingStepDto(step.getKey(), step.getLabel(), step.getDescription(),
                    isDone, isDismissed, isComplete));
        }

        int total = OnboardingStep.values().length;
        boolean allComplete = completed == total;
        int percent = total == 0 ? 100 : (completed * 100) / total;

        if (allComplete) {
            maybeCelebrate(tenantId);
        }

        return new OnboardingChecklistResponse(total, completed, percent, allComplete, steps);
    }

    /** Live "done" signals — each reuses an existing tenant-scoped repository/field. */
    private Map<OnboardingStep, Boolean> computeDoneFlags(Long tenantId) {
        var company = companyRepository.findByTenantId(tenantId).orElse(null);
        var settings = tenantSettingsRepository.findByTenantId(tenantId).orElse(null);

        boolean messagingConfigured = settings != null
                && (StringUtils.hasText(settings.getSmtpHost())
                    || StringUtils.hasText(settings.getWhatsAppApiKeyEnc()));

        Map<OnboardingStep, Boolean> done = new EnumMap<>(OnboardingStep.class);
        done.put(OnboardingStep.SET_LOGO, company != null && StringUtils.hasText(company.getLogoUrl()));
        // > 1 = at least one user beyond the auto-provisioned admin
        done.put(OnboardingStep.INVITE_TEAM, userRepository.countByTenantIdAndDeletedAtIsNull(tenantId) > 1);
        done.put(OnboardingStep.ADD_MASTER, hotelRepository.existsByTenantId(tenantId));
        done.put(OnboardingStep.CONFIGURE_MESSAGING, messagingConfigured);
        done.put(OnboardingStep.CREATE_LEAD, leadRepository.countByTenantIdAndDeletedAtIsNull(tenantId) > 0);
        done.put(OnboardingStep.CREATE_QUOTATION, quotationRepository.existsByTenantIdAndDeletedAtIsNull(tenantId));
        done.put(OnboardingStep.CREATE_BOOKING, bookingRepository.countByTenant(tenantId) > 0);
        return done;
    }

    /** Fire the congratulations notification exactly once per tenant. */
    private void maybeCelebrate(Long tenantId) {
        if (markerRepository.existsByTenantIdAndStepKey(tenantId, CELEBRATION_KEY)) {
            return; // already celebrated
        }
        markerRepository.save(OnboardingStepMarker.builder()
                .tenantId(tenantId)
                .stepKey(CELEBRATION_KEY)
                .dismissed(true)
                .build());

        List<Long> recipients = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();
        if (recipients.isEmpty()) {
            return;
        }

        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("ONBOARDING_COMPLETE")
                .tenantId(tenantId)
                .recipientUserIds(recipients)
                .title("You're all set!")
                .message("Your workspace setup is complete — nice work getting everything configured.")
                .referenceType("ONBOARDING")
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    @Transactional
    public void dismissStep(String stepKey) {
        setDismissed(stepKey, true);
    }

    /** Un-dismiss a step so it re-appears (e.g. the tenant changes their mind). */
    @Transactional
    public void restoreStep(String stepKey) {
        setDismissed(stepKey, false);
    }

    private void setDismissed(String stepKey, boolean value) {
        Long tenantId = requireTenant();
        OnboardingStep step;
        try {
            step = OnboardingStep.valueOf(stepKey);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Unknown onboarding step: " + stepKey);
        }
        markerRepository.findByTenantIdAndStepKey(tenantId, step.getKey())
                .ifPresentOrElse(
                        marker -> {
                            marker.setDismissed(value);
                            markerRepository.save(marker);
                        },
                        () -> markerRepository.save(OnboardingStepMarker.builder()
                                .tenantId(tenantId)
                                .stepKey(step.getKey())
                                .dismissed(value)
                                .build()));
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }
}