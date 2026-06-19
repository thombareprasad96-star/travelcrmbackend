package com.crm.travelcrm.notification.domain.enums;

/**
 * Canonical notification type constants shared across modules.
 *
 * <p>The {@code Notification} entity stores {@code type} as a free-form String (so any
 * module can introduce new types without touching this module). Publishers may use
 * {@code NotificationType.LEAD_CREATED.name()} for consistency, but are not required to.
 */
public enum NotificationType {
    LEAD_CREATED,
    LEAD_UPDATED,
    LEAD_ASSIGNED,
    LEAD_STATUS_CHANGED,
    BOOKING_CREATED,
    BOOKING_CONFIRMED,
    BOOKING_CANCELLED,
    BOOKING_STATUS_CHANGED,
    BOOKING_PAYMENT_UPDATED,
    BOOKING_ASSIGNED,
    CUSTOMER_CREATED,
    VENDOR_ADDED,
    QUOTATION_CREATED,
    QUOTATION_STATUS_CHANGED,
    REMINDER_DUE,
    SYSTEM_BROADCAST
}