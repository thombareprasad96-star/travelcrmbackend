package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** The four hero stat cards on the Booking Revenue page. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSummaryDTO {
    private BigDecimal totalRevenue;
    private BigDecimal netProfit;
    private double     avgNetMargin;
    private BigDecimal outstandingDue;
}