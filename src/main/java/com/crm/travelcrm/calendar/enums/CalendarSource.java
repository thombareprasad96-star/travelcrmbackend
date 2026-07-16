package com.crm.travelcrm.calendar.enums;

/**
 * The origin of a {@link com.crm.travelcrm.calendar.dto.CalendarEvent} on the unified calendar.
 *
 * <p>{@code TASK} / {@code REMINDER} are first-class editable rows; the rest are read-only events
 * <b>derived</b> from bookings (trip start dates, an inferred payment-due on the travel date, and
 * best-effort flight / hotel / visa service-lines). Used both as the event discriminator and as the
 * {@code ?categories=} filter token on the feed.
 */
public enum CalendarSource {
    TASK,
    REMINDER,
    TRIP,
    PAYMENT_DUE,
    FLIGHT,
    HOTEL_CHECKIN,
    VISA;

    /** Human legend label shown on the calendar. */
    public String label() {
        return switch (this) {
            case TASK          -> "Tasks";
            case REMINDER      -> "Follow-ups";
            case TRIP          -> "Confirmed Trips";
            case PAYMENT_DUE   -> "Payments Due";
            case FLIGHT        -> "Flights";
            case HOTEL_CHECKIN -> "Hotel Check-ins";
            case VISA          -> "Visa Appointments";
        };
    }
}