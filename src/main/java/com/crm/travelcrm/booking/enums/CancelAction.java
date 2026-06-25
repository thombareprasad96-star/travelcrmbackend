package com.crm.travelcrm.booking.enums;

/**
 * What to do with the associated lead when a booking is cancelled. The booking itself is
 * ALWAYS retained (status {@code CANCELLED}) for audit/financial history regardless of choice.
 */
public enum CancelAction {

    /** Cancel the booking and re-activate the source lead (stage → REOPENED). Links preserved. */
    MOVE_TO_LEAD,

    /**
     * Cancel the booking and HARD-delete the associated lead (irreversible). The booking is
     * retained with its customer/destination snapshot and its lead link nulled, so it never
     * dangles. High-privilege only ({@code LEAD_PERMANENT_DELETE}).
     */
    PERMANENT_DELETE_LEAD
}