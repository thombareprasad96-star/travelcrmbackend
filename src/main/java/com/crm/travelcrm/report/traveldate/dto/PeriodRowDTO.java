package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A period row of the analysis table (trend + percentage of total revenue). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodRowDTO {
    private String     month;
    private long       bookings;
    private long       travelers;
    private BigDecimal revenue;
    private int        avgDuration;
    private double     pctOfTotal;
}