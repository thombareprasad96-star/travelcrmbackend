package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A peak travel date (right sidebar). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeakDateDTO {
    private String date;      // "Jul 15, 2026"
    private long   bookings;
    private String label;     // "Peak"
}