package com.crm.travelcrm.bookingreminder.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BookingReminderStatsDto {
    private long total;
    private long pending;
    private long sent;
    private long completed;
}