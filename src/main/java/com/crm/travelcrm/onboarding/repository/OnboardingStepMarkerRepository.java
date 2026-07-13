package com.crm.travelcrm.onboarding.repository;

import com.crm.travelcrm.onboarding.entity.OnboardingStepMarker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped finders for onboarding markers (idiom copied from {@code UsageAlertMarkerRepository}).
 * Always pass {@code TenantContext.getTenantId()} explicitly — the Hibernate tenant filter does not
 * apply to derived {@code findById}-style calls.
 */
@Repository
public interface OnboardingStepMarkerRepository extends JpaRepository<OnboardingStepMarker, Long> {

    /** Exactly-once guard for the celebration sentinel. */
    boolean existsByTenantIdAndStepKey(Long tenantId, String stepKey);

    /** All markers (dismiss rows + the sentinel) for a tenant, in one query. */
    List<OnboardingStepMarker> findByTenantId(Long tenantId);

    /** Upsert target for dismiss/restore of a single step. */
    Optional<OnboardingStepMarker> findByTenantIdAndStepKey(Long tenantId, String stepKey);
}