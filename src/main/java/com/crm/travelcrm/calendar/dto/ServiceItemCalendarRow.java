package com.crm.travelcrm.calendar.dto;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Flat projection of a booking service-line joined to its booking, for calendar plotting of
 * flight / hotel / visa events. Populated by a JPQL constructor expression in
 * {@code CalendarServiceItemQueryRepository} so no full {@code Booking}/{@code BookingServiceItem}
 * entities (or their lazy collections) are loaded.
 */
public record ServiceItemCalendarRow(
        UUID serviceItemPublicId,
        String serviceType,
        String title,
        LocalDate serviceDate,
        LocalDate endDate,
        ServiceItemStatus status,
        UUID bookingPublicId,
        String customerName,
        String destination
) {
}
