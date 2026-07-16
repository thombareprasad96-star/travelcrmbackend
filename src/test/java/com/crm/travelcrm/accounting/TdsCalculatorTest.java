package com.crm.travelcrm.accounting;

import com.crm.travelcrm.accounting.tds.dto.TdsResult;
import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import com.crm.travelcrm.accounting.tds.service.TdsCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TdsCalculator}: section rate with PAN, 206AA 20% uplift without PAN. */
class TdsCalculatorTest {

    private final TdsCalculator calc = new TdsCalculator(
            new BigDecimal("0.02"), new BigDecimal("0.05"), new BigDecimal("0.10"), new BigDecimal("0.20"));

    @Test
    void sectionRateAppliesWhenVendorHasPan() {
        TdsResult r = calc.compute(new BigDecimal("100000"), TdsSection.SEC_194C, true);
        assertEquals(0, r.amount().compareTo(new BigDecimal("2000.00")));   // 2%
        assertEquals(0, r.ratePct().compareTo(new BigDecimal("2.00")));
    }

    @Test
    void noPanUpliftsToTwentyPercentUnder206AA() {
        TdsResult r = calc.compute(new BigDecimal("100000"), TdsSection.SEC_194C, false);
        assertEquals(0, r.amount().compareTo(new BigDecimal("20000.00")));  // max(2%, 20%)
        assertEquals(0, r.ratePct().compareTo(new BigDecimal("20.00")));
    }

    @Test
    void noPanDoesNotLowerARateAlreadyAboveTwentyIsNotApplicableButHigherOfHolds() {
        // 194J 10% with PAN stays 10%; without PAN → 20%
        assertEquals(0, calc.compute(new BigDecimal("100000"), TdsSection.SEC_194J, true)
                .amount().compareTo(new BigDecimal("10000.00")));
        assertEquals(0, calc.compute(new BigDecimal("100000"), TdsSection.SEC_194J, false)
                .amount().compareTo(new BigDecimal("20000.00")));
    }

    @Test
    void nullSectionMeansNoTds() {
        assertEquals(0, calc.compute(new BigDecimal("100000"), null, true).amount().compareTo(BigDecimal.ZERO));
    }
}