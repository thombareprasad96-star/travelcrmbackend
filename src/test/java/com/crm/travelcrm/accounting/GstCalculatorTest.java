package com.crm.travelcrm.accounting;

import com.crm.travelcrm.accounting.tax.dto.GstLineInput;
import com.crm.travelcrm.accounting.tax.dto.GstQuote;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import com.crm.travelcrm.accounting.tax.service.GstCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-math tests for {@link GstCalculator} — no Spring context. */
class GstCalculatorTest {

    private final GstCalculator calc = new GstCalculator();

    private GstLineInput line(String taxable, String rate) {
        return GstLineInput.builder()
                .description("svc").hsnSac("998552")
                .taxableValue(new BigDecimal(taxable)).gstRatePct(new BigDecimal(rate))
                .cessPct(BigDecimal.ZERO).itcEligible(false).build();
    }

    @Test
    void intraStateSplitsGstIntoEqualCgstSgstThatResumToGst() {
        // 10000 @ 18% = 1800 GST → 900 + 900
        GstQuote q = calc.calculate(List.of(line("10000", "18")), SupplyType.INTRA_STATE, true);
        assertEquals(0, q.getTotalCgst().compareTo(new BigDecimal("900.00")));
        assertEquals(0, q.getTotalSgst().compareTo(new BigDecimal("900.00")));
        assertEquals(0, q.getTotalIgst().compareTo(BigDecimal.ZERO));
        assertEquals(0, q.getTotalCgst().add(q.getTotalSgst()).compareTo(new BigDecimal("1800.00")));
        assertEquals(0, q.getTotalWithGst().compareTo(new BigDecimal("11800.00")));
    }

    @Test
    void interStateChargesFullIgst() {
        GstQuote q = calc.calculate(List.of(line("10000", "5")), SupplyType.INTER_STATE, true);
        assertEquals(0, q.getTotalIgst().compareTo(new BigDecimal("500.00")));
        assertEquals(0, q.getTotalCgst().compareTo(BigDecimal.ZERO));
        assertEquals(0, q.getTotalSgst().compareTo(BigDecimal.ZERO));
    }

    @Test
    void oddGstStillReSumsExactlyViaSgstRemainder() {
        // 1001 @ 5% = 50.05 → cgst 25.03 (HALF_UP), sgst = 25.02, sum = 50.05
        GstQuote q = calc.calculate(List.of(line("1001", "5")), SupplyType.INTRA_STATE, true);
        BigDecimal gst = q.getTotalCgst().add(q.getTotalSgst());
        assertEquals(0, gst.compareTo(new BigDecimal("50.05")), "halves must re-sum to the exact GST");
    }

    @Test
    void applyGstFalseZeroesAllTaxButKeepsTaxable() {
        GstQuote q = calc.calculate(List.of(line("10000", "18")), SupplyType.INTRA_STATE, false);
        assertEquals(0, q.getTotalCgst().compareTo(BigDecimal.ZERO));
        assertEquals(0, q.getTotalSgst().compareTo(BigDecimal.ZERO));
        assertEquals(0, q.getTotalIgst().compareTo(BigDecimal.ZERO));
        assertEquals(0, q.getTotalTaxable().compareTo(new BigDecimal("10000.00")));
        assertEquals(0, q.getTotalWithGst().compareTo(new BigDecimal("10000.00")));
    }
}