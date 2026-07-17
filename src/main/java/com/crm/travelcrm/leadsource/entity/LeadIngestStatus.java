package com.crm.travelcrm.leadsource.entity;

/**
 * The outcome of one inbound delivery.
 *
 * <p><b>A proper enum, not a free String.</b> The {@code WaMessageLog} precedent stores its status as
 * an unconstrained String; that is rejected here because this value is a QUERY PREDICATE and a
 * partial-index predicate, not a label.
 */
public enum LeadIngestStatus {

    /** Logged on arrival, before verification. The starting state of every delivery. */
    RECEIVED,

    /** A new lead was created. */
    PROCESSED,

    /**
     * A repeat contact was appended to an existing open lead (owner decision 1).
     *
     * <p><b>Must stay distinct from {@link #PROCESSED}</b> — collapse them and decision 1's append
     * rate becomes unmeasurable, which is the only way to tell whether the behaviour is working.
     */
    APPENDED,

    /** A retry of a delivery already handled. Dropped on purpose. */
    DUPLICATE,

    /** Valid traffic that is not a lead: subscription echoes, test pings, delivery-status callbacks. */
    IGNORED,

    /** The payload only pointed at leads; a {@code FetchHandle} is queued to retrieve them. */
    DEFERRED,

    /**
     * The plan's lead cap rejected it (owner decision 9).
     *
     * <p>Exists so a quota-blocked enquiry is VISIBLE rather than lost. The webhook still returns 200:
     * a 4xx to JustDial means retries and eventually a disabled integration, which loses the lead to
     * protect a counter.
     */
    QUARANTINED_QUOTA,

    /** Verification or parsing failed, or creation threw. The raw payload is retained for debugging. */
    FAILED
}
