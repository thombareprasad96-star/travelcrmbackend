package com.crm.travelcrm.booking.enums;

/**
 * What to do with the associated lead when a booking is cancelled. The booking itself is
 * ALWAYS retained (status {@code CANCELLED}) for audit/financial history regardless of choice.
 */
public enum CancelAction {

    /** Cancel the booking and re-activate the source lead (stage → REOPENED). Links preserved. */
    MOVE_TO_LEAD,

    /**
     * Cancel the booking and move the associated lead to Trash (recoverable soft-delete) — its
     * quotations cascade-trash with it. The booking is retained with its snapshots and keeps its
     * lead link, so restoring the lead from Trash reconnects them. High-privilege only
     * ({@code LEAD_PERMANENT_DELETE}). Despite the name, this is no longer a hard delete: only
     * Trash delete-now and the 30-day auto-purge ever remove the lead physically.
     */
    PERMANENT_DELETE_LEAD
}