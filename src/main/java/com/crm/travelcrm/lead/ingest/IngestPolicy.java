package com.crm.travelcrm.lead.ingest;

/**
 * Which behaviours a {@code createLead} call gets. Orthogonal to {@link LeadActor}: the actor says
 * <em>who</em>, the policy says <em>what rules apply to this call</em>.
 *
 * <p>They are kept separate because they genuinely vary independently — a SYSTEM actor replaying a
 * quarantined lead after a plan upgrade wants MACHINE's deferred publishing but not necessarily its
 * assignment arm — and because collapsing them would make {@code actor.isHuman()} silently decide
 * transaction semantics, which is exactly the kind of coupling that is invisible until it bites.
 */
public enum IngestPolicy {

    /**
     * A human at a keyboard. Quota over-cap throws (the user sees it and can act); the explicit
     * assignee on the request is honoured; the {@code LEAD_CREATED} notification is published inside
     * the create call — <b>strictly last</b>, after the assignment audit, because publishing clears
     * {@code TenantContext} on the publisher's own thread ({@code NotifyEventListener:38}).
     */
    INTERACTIVE,

    /**
     * Inbound ingestion from a lead-source integration. Differences that matter:
     *
     * <ul>
     *   <li><b>Quota over-cap quarantines rather than throwing</b> — a webhook cannot act on a 403
     *       and the provider would retry forever, losing the lead.</li>
     *   <li><b>Assignment ignores any requested assignee</b> and runs {@code assignForInbound}.</li>
     *   <li><b>Nothing is published here.</b> Events are handed back for the non-transactional
     *       gateway to publish <em>after commit</em>. Publishing inside would clear
     *       {@code TenantContext} for the rest of the ingest call — the same mechanism that already
     *       bites the audit write today — and would also fire an SSE push for a lead a later
     *       rollback removes.</li>
     * </ul>
     */
    MACHINE
}