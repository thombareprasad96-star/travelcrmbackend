package com.crm.travelcrm.accounting;

import com.crm.travelcrm.accounting.support.FinancialYear;
import com.crm.travelcrm.accounting.support.IndianAmountWords;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FinancialYear} boundary (Apr–Mar) + {@link IndianAmountWords}. */
class FinancialYearTest {

    @Test
    void aprilStartsANewFinancialYear() {
        FinancialYear fy = FinancialYear.of(LocalDate.of(2026, 4, 1));
        assertEquals(2026, fy.startYear());
        assertEquals("2026-27", fy.label());
        assertEquals("2627", fy.compact());
    }

    @Test
    void marchBelongsToThePriorFinancialYear() {
        FinancialYear fy = FinancialYear.of(LocalDate.of(2026, 3, 31));
        assertEquals(2025, fy.startYear());
        assertEquals("2025-26", fy.label());
        assertEquals("2526", fy.compact());
    }

    @Test
    void amountInWordsUsesIndianSystem() {
        assertEquals("Rupees Five Lakh Only", IndianAmountWords.toWords(new BigDecimal("500000.00")));
        assertEquals("Rupees Zero Only", IndianAmountWords.toWords(BigDecimal.ZERO));
        // Paise are rendered and the lakh/thousand grouping is used.
        String s = IndianAmountWords.toWords(new BigDecimal("123450.50"));
        assertTrue(s.startsWith("Rupees One Lakh Twenty Three Thousand Four Hundred"), s);
        assertTrue(s.endsWith("Fifty Paise Only"), s);
    }
}