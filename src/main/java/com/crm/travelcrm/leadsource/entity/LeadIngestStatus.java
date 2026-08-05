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

    /**
     * An OPEN lead already exists for this contact, but the append rule did not fire.
     *
     * <p>Reached when {@code validateNoDuplicates} blocks the create after
     * {@code findOpenLeadByPhone} found nothing — the two use different keys. The append probe
     * matches on the CANONICAL phone only; the duplicate check also rejects on EMAIL. So an enquiry
     * carrying a known email under a new phone number falls between them: not appended, not created.
     *
     * <p>Distinct from {@link #QUARANTINED_QUOTA} because the remedy is different — nothing to
     * upgrade, someone has to open the existing lead and log the enquiry against it.
     */
    QUARANTINED_DUPLICATE,

    /**
     * The only matching lead is in Trash, so creating this one would resurrect a deleted contact
     * behind the tenant's back.
     *
     * <p>The human path answers this with a "restore available" 409 the UI turns into a Restore
     * button. A provider has no such UI, so the delivery is quarantined and the desk is told which
     * record to restore.
     */
    QUARANTINED_TRASHED,

    /** Verification or parsing failed, or creation threw. The raw payload is retained for debugging. */
    FAILED
}
