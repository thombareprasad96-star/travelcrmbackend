package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Revenue breakdown panel. {@code refunded} is the sum of {@code Booking.refundedAmount} — the
 *  money actually disbursed back to customers, not the value of refunded-status bookings. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueBreakdownDTO {
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal refunded;
}