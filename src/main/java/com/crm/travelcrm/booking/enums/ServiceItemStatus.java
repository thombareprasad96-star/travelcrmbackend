package com.crm.travelcrm.booking.enums;

/**
 * Operational status of a single service line on a booking (a hotel, a transfer, a flight …).
 * Independent of the parent {@link BookingStatus}: a booking can be CONFIRMED while one of its
 * service lines is still PENDING vendor confirmation.
 */
public enum ServiceItemStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}