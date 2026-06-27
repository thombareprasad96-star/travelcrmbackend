package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Four hero stat cards. {@code totalTravelers} has no source on Booking and is 0. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelSummaryDTO {
    private long       totalBookings;
    private long       totalTravelers;
    private double     avgPerPeriod;
    private BigDecimal totalRevenue;
}