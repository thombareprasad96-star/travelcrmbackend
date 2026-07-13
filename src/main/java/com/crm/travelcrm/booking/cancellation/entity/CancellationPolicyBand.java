package com.crm.travelcrm.booking.cancellation.entity;

import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.math.BigDecimal;

/**
 * One slab of a {@link CancellationPolicy}: "cancel with at least {@code minDaysBeforeDeparture}
 * days to go → deduct {@code deductionValue} ({@link DeductionType})".
 *
 * <p><b>Single-threshold, floor-anchored model.</b> Each band carries only a lower bound in
 * days-before-departure; the calculator selects the band with the largest
 * {@code minDaysBeforeDeparture} that is still {@code <= max(0, daysBeforeDeparture)}. Clamping the
 * day-count at zero means a same-day cancel or an already-departed no-show (negative days) always
 * lands on the {@code minDaysBeforeDeparture == 0} band — the most-restrictive slab — and can never
 * fall through to a zero charge. Because bands share no upper bound, adjacent slabs can neither
 * overlap nor leave a gap by construction (unlike a min/max pair), so an identical policy always
 * yields an identical charge regardless of row order.
 *
 * <p>Value type embedded in {@code cancellation_policy_bands}. Immutable in practice: a policy is
 * never edited in place — an edit writes a brand-new {@link CancellationPolicy} version row with its
 * own band rows, leaving any version a booking already pins byte-for-byte untouched.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationPolicyBand {

    /** Inclusive lower bound, in days before departure, at which this band's charge applies. >= 0. */
    @Column(name = "min_days_before_departure", nullable = false)
    private int minDaysBeforeDeparture;

    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_type", nullable = false, length = 10)
    private DeductionType deductionType;

    /** PERCENT: whole-number percent in [0,100]. FLAT: absolute amount >= 0. */
    @Column(name = "deduction_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal deductionValue;
}