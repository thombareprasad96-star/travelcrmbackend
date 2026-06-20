package com.crm.travelcrm.notification.domain.enums;

/**
 * Discriminator for the polymorphic {@code referencePublicId} on a notification — tells the
 * frontend which detail page to route to. Persisted via {@code @Enumerated(EnumType.STRING)}.
 *
 * <p>Publishers supply this as a free-form String on {@link com.crm.travelcrm.notification.api.NotifyEvent}
 * (keeping the module decoupled); it is parsed leniently via {@link #fromString} when the
 * notification is persisted, so an unknown/absent value simply yields {@code null}.
 */
public enum NotificationReferenceType {
    LEAD,
    BOOKING,
    REMINDER,
    CUSTOMER,
    VENDOR;

    /** Case-insensitive parse; returns {@code null} for blank or unrecognized values. */
    public static NotificationReferenceType fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}