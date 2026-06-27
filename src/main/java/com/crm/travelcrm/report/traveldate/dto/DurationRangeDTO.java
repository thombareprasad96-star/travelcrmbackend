package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A trip-duration bucket. Booking has no duration column, so this list is empty in practice. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DurationRangeDTO {
    private String range;
    private long   count;
    private int    pct;
}