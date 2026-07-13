package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicyBand;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import com.crm.travelcrm.booking.cancellation.repository.CancellationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Provisions the conservative tiered COMPANY-default cancellation policy every tenant needs before
 * its first cancellation. Without a default, resolution would fall through to a zero charge and — per
 * the elevated-permission design — an ordinary agent could not cancel at all on a fresh tenant. This
 * is invoked in the same transaction as tenant creation, and by a one-time backfill for pre-existing
 * tenants; both are idempotent.
 *
 * <p>The seeded slab (of the pre-tax base fare): &ge;30d → 10%, 15–29d → 25%, 7–14d → 50%,
 * &lt;7d / same-day / no-show → 100%. It is fully editable by the tenant afterwards. Its
 * {@code effectiveFrom} is the epoch floor so it governs bookings of any date until the tenant
 * publishes their own dated versions.
 */
@Component
@RequiredArgsConstructor
public class CancellationPolicySeeder {

    private static final Logger log = LogManager.getLogger(CancellationPolicySeeder.class);

    /** effectiveFrom floor so the seeded baseline covers a booking of any date (incl. back-dated). */
    private static final LocalDate EPOCH_FLOOR = LocalDate.of(1970, 1, 1);

    private final CancellationPolicyRepository policyRepository;

    /**
     * Ensure the tenant has a COMPANY default. No-op if one already exists (checked without the
     * Hibernate tenant filter via an explicit tenantId predicate). Joins the caller's transaction
     * when there is one (tenant creation) or runs in its own (backfill).
     */
    @Transactional
    public void ensureCompanyDefault(Long tenantId) {
        boolean exists = policyRepository
                .existsByTenantIdAndLevelAndOwnerPublicIdIsNullAndDeletedAtIsNull(
                        tenantId, CancellationPolicyLevel.COMPANY);
        if (exists) {
            return;
        }
        CancellationPolicy seeded = buildDefault(tenantId);
        policyRepository.save(seeded);
        log.info("Seeded default cancellation policy for tenant {}", tenantId);
    }

    private CancellationPolicy buildDefault(Long tenantId) {
        List<CancellationPolicyBand> bands = List.of(
                band(30, 10),   // >= 30 days  -> 10%
                band(15, 25),   // 15 - 29     -> 25%
                band(7, 50),    // 7 - 14      -> 50%
                band(0, 100)    // < 7 / same-day / no-show -> 100%
        );
        CancellationPolicy policy = CancellationPolicy.builder()
                .name("Company default")
                .level(CancellationPolicyLevel.COMPANY)
                .ownerPublicId(null)
                .version(1)
                .active(true)
                .effectiveFrom(EPOCH_FLOOR)
                .gstOnChargeApplicable(true)
                .tcsRefundable(true)
                .build();
        policy.setTenantId(tenantId);            // explicit — platform createTenant runs without TenantContext
        policy.getBands().addAll(bands);
        return policy;
    }

    private static CancellationPolicyBand band(int minDays, int percent) {
        return CancellationPolicyBand.builder()
                .minDaysBeforeDeparture(minDays)
                .deductionType(DeductionType.PERCENT)
                .deductionValue(BigDecimal.valueOf(percent))
                .build();
    }
}