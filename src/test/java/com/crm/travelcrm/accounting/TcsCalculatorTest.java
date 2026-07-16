package com.crm.travelcrm.accounting;

import com.crm.travelcrm.accounting.tax.service.TcsCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 206C(1G) slab math for {@link TcsCalculator}: nil handling + ₹7L threshold split. */
class TcsCalculatorTest {

    // threshold 7,00,000 · below 5% · above 20%
    private final TcsCalculator calc =
            new TcsCalculator(new BigDecimal("700000"), new BigDecimal("0.05"), new BigDecimal("0.20"));

    @Test
    void whollyBelowThresholdTaxedAtLowerRate() {
        assertEquals(0, calc.compute(new BigDecimal("200000"), BigDecimal.ZERO)
                .compareTo(new BigDecimal("10000.00")));   // 200000 * 5%
    }

    @Test
    void spanningThresholdSplitsAcrossBothRates() {
        // prior 6,00,000 → 1,00,000 headroom @5% (5000) + 1,00,000 above @20% (20000) = 25000
        assertEquals(0, calc.compute(new BigDecimal("200000"), new BigDecimal("600000"))
                .compareTo(new BigDecimal("25000.00")));
    }

    @Test
    void whollyAboveThresholdTaxedAtHigherRate() {
        // prior already over the threshold → no headroom, all @20%
        assertEquals(0, calc.compute(new BigDecimal("100000"), new BigDecimal("800000"))
                .compareTo(new BigDecimal("20000.00")));
    }

    @Test
    void zeroOrNullValueIsNil() {
        assertEquals(0, calc.compute(BigDecimal.ZERO, BigDecimal.ZERO).compareTo(BigDecimal.ZERO));
        assertEquals(0, calc.compute(null, null).compareTo(BigDecimal.ZERO));
    }
}