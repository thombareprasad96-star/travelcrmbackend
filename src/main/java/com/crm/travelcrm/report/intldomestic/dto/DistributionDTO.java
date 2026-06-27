package com.crm.travelcrm.report.intldomestic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Donut-chart split between International and Domestic. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributionDTO {
    private int        intlRevenuePct;
    private int        domRevenuePct;
    private int        intlBookingsPct;
    private int        domBookingsPct;
    private BigDecimal totalRevenue;
    private long       totalBookings;
    private BigDecimal intlRevenue;
    private BigDecimal domRevenue;
    private long       intlBookings;
    private long       domBookings;
}