package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** The hero stat cards on the Booking Revenue page. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSummaryDTO {

    /**
     * Package sales — Σ customerAmount of bookings that are NOT cancelled or refunded. A cancelled
     * booking is not revenue: nothing was delivered, and only the charge the agency retained under
     * its cancellation policy was ever earned. That retained figure is reported separately below.
     */
    private BigDecimal totalRevenue;

    /**
     * Cancellation charge retained in the period, NET of GST/TCS. Its own line, never merged into
     * {@link #totalRevenue}: it is revenue with no service delivered behind it, so folding it in
     * corrupts margin %, realisation per pax and every conversion metric read off package sales.
     */
    private BigDecimal retainedCancellationCharges;

    /** {@link #totalRevenue} + {@link #retainedCancellationCharges}. */
    private BigDecimal agencyRevenue;

    /** Profit on delivered travel — cancelled bookings excluded. */
    private BigDecimal netProfit;

    /**
     * Profit on cancelled bookings: retained charge less the vendor and internal costs that could
     * not be recovered. Frequently NEGATIVE — a 25% cancellation slab does not cover a 100%-sunk
     * visa or air cost — which is exactly why it is worth seeing on its own.
     */
    private BigDecimal cancelledProfit;

    /** {@link #netProfit} + {@link #cancelledProfit} — total operating gross profit. */
    private BigDecimal totalProfit;

    private double     avgNetMargin;
    private BigDecimal outstandingDue;
}