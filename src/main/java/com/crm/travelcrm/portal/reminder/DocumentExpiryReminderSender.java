package com.crm.travelcrm.portal.reminder;

/**
 * Delivery hook for document-expiry reminders. Kept abstract so the actual channel (in-app
 * notification, SMS, WhatsApp, email) can be wired without touching the scheduling/idempotency
 * logic. Default impl logs (see {@link LoggingDocumentExpiryReminderSender}).
 */
public interface DocumentExpiryReminderSender {
    void send(DocumentExpiryReminder reminder);
}
