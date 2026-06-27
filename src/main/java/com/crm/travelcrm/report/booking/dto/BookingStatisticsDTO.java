package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Booking statistics panel. {@code international}/{@code domestic} need a trip-type column the
 * Booking entity does not have; all bookings are counted as {@code domestic} (international = 0).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingStatisticsDTO {
    private long international;
    private long domestic;
    private long confirmed;
    private long completed;
    private long cancelled;
}