package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.repository.CancellationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only resolution of which cancellation policy version governs a booking. It <b>never</b>
 * provisions, pins, or mutates anything — so it is safe to call from a dry-run preview
 * ({@code @Transactional(readOnly = true)}). Pinning happens only at booking create/convert; this
 * class just answers "which version".
 *
 * <p><b>Anti-retroactivity.</b> At booking time we pin the exact version the customer was quoted
 * under (the quotation's policy), or — absent a quotation — the company default in force as of the
 * booking date. Because policy versions are immutable, a later edit produces a new version and can
 * never change a booking already pinned.
 */
@Component
@RequiredArgsConstructor
public class CancellationPolicyResolver {

    private final CancellationPolicyRepository policyRepository;

    /**
     * Resolve the version to PIN on a new booking.
     *
     * @param quotationPolicyPublicId the policy the source quotation carried (a specific version), or null
     * @param bookingDate             used for point-in-time company-default resolution
     * @return the version to pin, or empty if the tenant has no company default yet (pin stays null;
     *         cancel-time resolution then falls back with a provenance note)
     */
    public Optional<CancellationPolicy> resolveForNewBooking(Long tenantId,
                                                             UUID quotationPolicyPublicId,
                                                             LocalDate bookingDate) {
        if (quotationPolicyPublicId != null) {
            Optional<CancellationPolicy> quoted =
                    policyRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(quotationPolicyPublicId, tenantId);
            if (quoted.isPresent()) {
                return quoted;
            }
            // Quoted policy vanished (hard-deleted / cross-tenant) — fall back to the company default.
        }
        return companyDefaultAsOf(tenantId, bookingDate != null ? bookingDate : LocalDate.now());
    }

    /** The active company-default version whose effectiveFrom is on or before {@code date}. */
    public Optional<CancellationPolicy> companyDefaultAsOf(Long tenantId, LocalDate date) {
        List<CancellationPolicy> hits = policyRepository.findCompanyVersionAsOf(
                tenantId, date != null ? date : LocalDate.now(), PageRequest.of(0, 1));
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * Load the exact version a booking pinned (used at cancel time). May return a superseded
     * (inactive) version — that is correct: the booking is judged by the version it was created
     * under. Empty only if the pinned row was physically removed (guarded against elsewhere).
     */
    public Optional<CancellationPolicy> loadPinned(Long tenantId, UUID policyPublicId) {
        if (policyPublicId == null) return Optional.empty();
        return policyRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(policyPublicId, tenantId);
    }
}