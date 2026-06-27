package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A period bucket for the trends chart. {@code month} holds the period label for any analysisType.
 *  {@code travelers}/{@code avgDuration} have no source on Booking and are 0. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendDataDTO {
    private String     month;
    private long       bookings;
    private long       travelers;
    private BigDecimal revenue;
    private int        avgDuration;
}