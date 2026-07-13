package com.crm.travelcrm.platform.billing.proration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-math tests for {@link ProrationCalculator}. No Spring context — the calculator is a pure
 * function. A 30-day period (June) keeps the fractions exact and easy to verify by hand.
 */
class ProrationCalculatorTest {

    private final ProrationCalculator calc = new ProrationCalculator();

    private static final LocalDate JUNE_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_END = LocalDate.of(2026, 6, 30);   // 30-day period

    private static BigDecimal money(String v) {
        return new BigDecimal(v).setScale(2);
    }

    @Test
    void upgradeMidCycle_chargesProratedDifference() {
        // Change on Jun 16 → remaining = Jun 16..30 inclusive = 15 of 30 days = 0.5.
        // (2000 - 1000) * 0.5 = 500.00
        ProrationResult r = calc.calculate(money("1000"), money("2000"),
                LocalDate.of(2026, 6, 16), JUNE_START, JUNE_END);

        assertEquals(15, r.remainingDays());
        assertEquals(30, r.periodDays());
        assertEquals(money("500.00"), r.amount());
        assertTrue(r.isCharge());
        assertTrue(r.hasAdjustment());
    }

    @Test
    void downgradeMidCycle_creditsProratedDifference() {
        // (1000 - 2000) * 0.5 = -500.00 → credit
        ProrationResult r = calc.calculate(money("2000"), money("1000"),
                LocalDate.of(2026, 6, 16), JUNE_START, JUNE_END);

        assertEquals(money("-500.00"), r.amount());
        assertTrue(r.isCredit());
    }

    @Test
    void samePrice_noAdjustment() {
        ProrationResult r = calc.calculate(money("1500"), money("1500"),
                LocalDate.of(2026, 6, 16), JUNE_START, JUNE_END);

        assertEquals(0, r.amount().signum());
        assertFalse(r.hasAdjustment());
    }

    @Test
    void changeOnFirstDay_chargesWholePeriod() {
        // Whole period remaining (30/30) → full difference.
        ProrationResult r = calc.calculate(money("1000"), money("2000"),
                JUNE_START, JUNE_START, JUNE_END);

        assertEquals(30, r.remainingDays());
        assertEquals(money("1000.00"), r.amount());
    }

    @Test
    void changeOnLastDay_chargesOneDay() {
        // 1 of 30 days remaining → (2000-1000)/30 = 33.333.. → 33.33
        ProrationResult r = calc.calculate(money("1000"), money("2000"),
                JUNE_END, JUNE_START, JUNE_END);

        assertEquals(1, r.remainingDays());
        assertEquals(money("33.33"), r.amount());
    }

    @Test
    void periodInThePast_noAdjustment() {
        ProrationResult r = calc.calculate(money("1000"), money("2000"),
                LocalDate.of(2026, 7, 1), JUNE_START, JUNE_END);

        assertFalse(r.hasAdjustment());
        assertEquals(0, r.remainingDays());
    }

    @Test
    void nullOldPrice_treatedAsZero() {
        // Upgrade from a free plan (null price) to 3000 on day 1 → full 3000.
        ProrationResult r = calc.calculate(null, money("3000"), JUNE_START, JUNE_START, JUNE_END);
        assertEquals(money("3000.00"), r.amount());
    }
}