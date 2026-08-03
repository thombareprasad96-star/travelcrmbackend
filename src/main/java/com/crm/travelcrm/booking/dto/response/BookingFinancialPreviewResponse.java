package com.crm.travelcrm.booking.dto.response;

import com.crm.travelcrm.booking.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * What {@code POST /api/bookings/preview} answers: the exact figures the server would stamp on a
 * booking created with the posted amounts, under THIS tenant's accounting settings. The create form
 * renders these verbatim — every value is scale-2, matching what a real create would persist.
 *
 * <p>{@code netProfit} mirrors the create path exactly: {@code customerAmount − vendorCost}, with
 * the internal-costs term zero because an unsaved booking has no expense ledger yet.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingFinancialPreviewResponse {

    private BigDecimal gst;
    private BigDecimal tcs;

    /** customerAmount + gst + tcs — what the customer actually owes. */
    private BigDecimal totalPayable;

    /** customerAmount − vendorCost (pre-tax; GST/TCS are pass-throughs, never income). */
    private BigDecimal netProfit;

    /** totalPayable − paidAmount, floored at zero. */
    private BigDecimal pendingAmount;

    private PaymentStatus paymentStatus;
}
