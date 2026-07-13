package com.crm.travelcrm.platform.billing.proration;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure proration math for a mid-cycle plan change. The delta charged/credited is the difference in
 * monthly price applied to the UNUSED fraction of the current billing period:
 *
 * <pre>
 *   delta = (newMonthly - oldMonthly) × (remainingDays / periodDays)
 * </pre>
 *
 * <p>Days are inclusive of both ends. The change takes effect from {@code today} (or {@code periodStart}
 * if the change is dated before the period opens). Returns a zero adjustment when there is no live
 * period ({@code periodEnd} in the past / missing) or the two plans cost the same. Stateless and
 * side-effect free so it is unit-tested without a Spring context.
 */
@Component
public class ProrationCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 10;

    public ProrationResult calculate(BigDecimal oldMonthly, BigDecimal newMonthly,
                                     LocalDate today, LocalDate periodStart, LocalDate periodEnd) {
        BigDecimal oldM = oldMonthly != null ? oldMonthly : BigDecimal.ZERO;
        BigDecimal newM = newMonthly != null ? newMonthly : BigDecimal.ZERO;

        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)
                || today.isAfter(periodEnd)) {
            return zero(0, 0);
        }

        long periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
        LocalDate effectiveFrom = today.isBefore(periodStart) ? periodStart : today;
        long remainingDays = ChronoUnit.DAYS.between(effectiveFrom, periodEnd) + 1;
        remainingDays = Math.max(0, Math.min(remainingDays, periodDays));

        BigDecimal diff = newM.subtract(oldM);
        if (diff.signum() == 0 || remainingDays == 0) {
            return zero(remainingDays, periodDays);
        }

        BigDecimal fraction = BigDecimal.valueOf(remainingDays)
                .divide(BigDecimal.valueOf(periodDays), RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal delta = diff.multiply(fraction).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new ProrationResult(delta, remainingDays, periodDays);
    }

    private static ProrationResult zero(long remainingDays, long periodDays) {
        return new ProrationResult(BigDecimal.ZERO.setScale(MONEY_SCALE), remainingDays, periodDays);
    }
}