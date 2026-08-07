package com.crm.travelcrm.lead.alert;

/**
 * The SSE event names the claim window pushes on the shared notification stream.
 *
 * <p><b>None of these is {@code "notification"}.</b> That name is the bell's feed and its payload
 * is a {@code NotificationResponseDTO}; pushing a lead alert under it would make every existing
 * client try to render a lead as a notification row and would inflate the unread badge with events
 * that are not notifications. A distinct name means an older client simply ignores what it does not
 * listen for, which is the correct degradation.
 *
 * <p>They ride the SAME EventSource as the bell. A second connection per tab would double the
 * server's open-connection count for no benefit, and the reconnect-with-fresh-token logic that
 * makes the existing stream survive an expiring JWT would have to be duplicated and kept in step.
 */
public final class LeadAlertEvent {

    private LeadAlertEvent() {}

    /**
     * A lead entered the claim pool and is open to anyone. Payload: {@code LeadAlertDto}.
     *
     * <p>Also fired when a manager REOPENS a mistakenly-locked lead. That is deliberate rather than
     * a separate quieter event: reopening means "somebody please pick this up", and a silent
     * reinsertion would drop the lead into a list nobody happens to be looking at — the exact
     * failure the broadcast exists to prevent.
     */
    public static final String NEW_LEAD = "lead-alert";

    /**
     * Ownership moved while the lead was still open — someone claimed or overrode it. Payload:
     * {@code LeadAlertDto} carrying the NEW owner and the bumped {@code claimVersion}, so every
     * other tab's Claim button re-arms with a version that will actually match.
     */
    public static final String CLAIMED = "lead-claimed";

    /**
     * The lead is locked: first contact was made, so it must disappear from every claim list.
     * Payload: {@code LeadAlertDto} with {@code openToClaim=false}.
     *
     * <p>Separate from {@link #CLAIMED} because the UI reactions differ in kind — a claim updates a
     * row in place, a lock removes it — and a client that had to infer which by inspecting
     * {@code openToClaim} would animate the wrong one every time the flag was absent.
     *
     * <p>Also fired on a post-lock REASSIGN. The lead is not in anyone's claim list to update, so
     * nothing is removed twice; this is what lets an open lead-detail view show the new owner
     * without a refresh.
     */
    public static final String LOCKED = "lead-locked";
}
