package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Revenue breakdown panel. {@code refunded} has no dedicated column and is returned 0. */
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