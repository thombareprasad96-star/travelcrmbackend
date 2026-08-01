package com.crm.travelcrm.accounting.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Percent ⇄ fraction conversion for tax rates, in one place.
 *
 * <p>The system stores every tenant-editable rate as a PERCENT (5.00 = 5%), because that is what a
 * tenant and their CA think and speak in, and what the settings screen shows. The calculators work
 * in FRACTIONS (0.05), because that is what you multiply a base by. Getting that boundary wrong is a
 * 100× error in someone's tax, so the conversion lives here rather than being open-coded at each
 * call site.
 *
 * <p>Scale 6 on the fraction is deliberate: a rate like 2.5% is 0.025, and 0.125% (a real TDS-style
 * figure) is 0.00125 — anything less than 6 places would quietly truncate a legitimate rate to zero.
 * The MONEY is still rounded to 2 at the end by each calculator; this scale only governs the
 * multiplier.
 */
public final class Percents {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int FRACTION_SCALE = 6;

    private Percents() {}

    /** 5.00 (percent) → 0.050000 (fraction). Null reads as zero. */
    public static BigDecimal toFraction(BigDecimal percent) {
        if (percent == null) return BigDecimal.ZERO;
        return percent.divide(HUNDRED, FRACTION_SCALE, RoundingMode.HALF_UP);
    }

    /** 0.05 (fraction) → 5.00 (percent). Null reads as zero. */
    public static BigDecimal toPercent(BigDecimal fraction) {
        if (fraction == null) return BigDecimal.ZERO;
        return fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }
}
