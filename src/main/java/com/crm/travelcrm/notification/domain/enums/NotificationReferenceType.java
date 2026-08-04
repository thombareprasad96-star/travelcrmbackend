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
    VENDOR,
    /**
     * Added with the All Tasks screen. Task notifications ({@code TASK_ASSIGNED}) had been published
     * with {@code referenceType("TASK")} since the task module shipped, but this enum did not list it
     * — so {@link #fromString} returned null and every one of them persisted
     * {@code reference_type = NULL} and could not be deep-linked from the bell. The DB CHECK
     * constraint is widened to match in V2 PART 18; adding a constant here without that migration
     * would fail at INSERT.
     */
    TASK;

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