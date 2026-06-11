package com.crm.travelcrm.notification.domain.enums;

public enum ReminderStatus {
    PENDING,
    /**
     * Internal: claimed by the scheduler poller via FOR UPDATE SKIP LOCKED.
     * Prevents double-firing across multiple app instances (RSK-002).
     * Not exposed in the API response.
     */
    PROCESSING,
    SENT,
    DISMISSED,
    SNOOZED
}