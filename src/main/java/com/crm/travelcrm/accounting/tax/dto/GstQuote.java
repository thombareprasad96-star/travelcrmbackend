package com.crm.travelcrm.accounting.tax.dto;

import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The immutable result of a GST computation over a set of lines — the value-object precedent mirrors
 * {@code CancellationQuote}. Carries the per-line tax breakup and the rolled-up totals. TCS, round-off
 * and the final invoice total are applied by the invoice service on top of this (they depend on
 * booking/settings context the pure GST math does not own).
 */
@Getter
@Builder
public class GstQuote {

    private final SupplyType supplyType;
    private final List<Line> lines;

    private final BigDecimal totalTaxable;
    private final BigDecimal totalCgst;
    private final BigDecimal totalSgst;
    private final BigDecimal totalIgst;
    private final BigDecimal totalCess;
    /** totalTaxable + all tax components (before TCS / round-off). */
    private final BigDecimal totalWithGst;

    /** One computed tax line. */
    @Getter
    @Builder
    public static class Line {
        private final String description;
        private final String hsnSac;
        private final BigDecimal taxableValue;
        private final BigDecimal gstRatePct;
        private final BigDecimal cgstAmt;
        private final BigDecimal sgstAmt;
        private final BigDecimal igstAmt;
        private final BigDecimal cessAmt;
        private final BigDecimal lineTotal;
        private final boolean itcEligible;
        private final Long serviceItemId;
        private final UUID serviceItemPublicId;
    }
}