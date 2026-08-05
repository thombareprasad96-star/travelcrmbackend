package com.crm.travelcrm.communication.enums;

/**
 * Lifecycle of a one-off scheduled send ({@code comm_scheduled_message}).
 *
 * <p>{@link #SCHEDULED} is the scheduler's query predicate, so it is indexed together with
 * {@code send_at}. A partial index on this state keeps the poll cheap as the table accumulates
 * years of {@link #SENT} rows.
 */
public enum ScheduledMessageStatus {

    /** Waiting for {@code sendAt}. The only state the scheduler picks up. */
    SCHEDULED,

    SENT,

    /** Every attempt failed. {@code lastError} carries the provider's reason. */
    FAILED,

    /** Withdrawn by a user before it fired. */
    CANCELLED
}
