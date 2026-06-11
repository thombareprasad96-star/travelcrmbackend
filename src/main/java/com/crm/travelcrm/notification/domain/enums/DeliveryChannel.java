package com.crm.travelcrm.notification.domain.enums;

/**
 * Delivery channels supported by the notification pipeline.
 * Adding a new channel = adding a new constant here + one class implementing
 * {@link com.crm.travelcrm.notification.api.NotificationChannel}.
 * The dispatcher never changes (O principle).
 */
public enum DeliveryChannel {
    IN_APP,
    SSE,
    EMAIL
    // Future: SMS, WHATSAPP, PUSH
}