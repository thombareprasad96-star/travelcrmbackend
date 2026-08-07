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
    EMAIL,
    /**
     * Outbound WhatsApp via the tenant's own provider account, handled by
     * {@code WhatsAppNotificationChannel} which delegates to {@code settings/WhatsAppMessagingService}.
     *
     * <p>Template-only: the provider accepts no free text outside a 24-hour session window, so the
     * event's title/message are passed as ordered template body values, not as a message body.
     * A tenant with no WhatsApp credentials configured is skipped silently — the other channels in
     * the same event still deliver.
     */
    WHATSAPP
    // Future: SMS, PUSH
}