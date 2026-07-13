package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyBandDto;
import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the structural invariants of a cancellation policy at save time, so a stored policy can
 * never produce a non-deterministic or accidentally-zero charge at cancel time:
 * <ul>
 *   <li>at least one band, and exactly one <b>floor band</b> at {@code minDaysBeforeDeparture == 0}
 *       (guarantees a same-day cancel / past-date no-show always matches the most-restrictive slab
 *       rather than falling through to a zero charge);</li>
 *   <li>no duplicate thresholds (two bands at the same day-count would be order-dependent);</li>
 *   <li>PERCENT value in [0, 100]; FLAT value &gt;= 0.</li>
 * </ul>
 * Thresholds need no gap/overlap check: single-threshold bands tile {@code [0, +inf)} by
 * construction once a floor band exists.
 */
@Component
public class CancellationPolicyValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public void validate(List<CancellationPolicyBandDto> bands) {
        if (bands == null || bands.isEmpty()) {
            throw new BusinessException("A cancellation policy needs at least one band.", HttpStatus.BAD_REQUEST);
        }

        Set<Integer> seen = new HashSet<>();
        boolean hasFloor = false;
        for (CancellationPolicyBandDto b : bands) {
            Integer min = b.getMinDaysBeforeDeparture();
            if (min == null || min < 0) {
                throw new BusinessException(
                        "Band 'days before departure' must be zero or greater.", HttpStatus.BAD_REQUEST);
            }
            if (!seen.add(min)) {
                throw new BusinessException(
                        "Two bands cannot share the same 'days before departure' (" + min + ").",
                        HttpStatus.BAD_REQUEST);
            }
            if (min == 0) {
                hasFloor = true;
            }

            DeductionType type = b.getDeductionType();
            BigDecimal value = b.getDeductionValue();
            if (type == null || value == null) {
                throw new BusinessException("Each band needs a deduction type and value.", HttpStatus.BAD_REQUEST);
            }
            if (value.signum() < 0) {
                throw new BusinessException("Band deduction value cannot be negative.", HttpStatus.BAD_REQUEST);
            }
            if (type == DeductionType.PERCENT && value.compareTo(HUNDRED) > 0) {
                throw new BusinessException(
                        "A PERCENT band cannot exceed 100 (got " + value + ").", HttpStatus.BAD_REQUEST);
            }
        }

        if (!hasFloor) {
            throw new BusinessException(
                    "A cancellation policy must include a band at 0 days before departure "
                            + "(the charge applied to a same-day cancel or a no-show).",
                    HttpStatus.BAD_REQUEST);
        }
    }
}