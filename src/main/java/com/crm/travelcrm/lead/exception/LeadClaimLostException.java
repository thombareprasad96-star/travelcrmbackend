package com.crm.travelcrm.lead.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * A claim, contact-stamp or reassign lost its race: the compare-and-swap matched no row because
 * somebody else moved first.
 *
 * <p><b>This is a normal outcome, not a fault.</b> Two agents pressing "Claim" on the same alert at
 * the same second is exactly what the feature invites, so the loser is owed a useful answer rather
 * than a generic 409: who holds the lead now, and the fresh {@code claimVersion} to re-submit with
 * if they still want it. Those ride in the error {@code details} map (the idiom
 * {@code RestoreAvailableException} established) so the UI can render "now owned by Priya" and
 * re-enable the button, instead of "conflict — reload the page".
 *
 * <p>{@link #reason} distinguishes the three ways to lose, because they need different UI:
 * a stale version is retryable, a locked lead is not, and a vanished lead should leave the list.
 */
@Getter
public class LeadClaimLostException extends RuntimeException {

    /** Why the compare-and-swap matched nothing. */
    public enum Reason {
        /** Someone else claimed it first; the lead is still open and can be claimed again. */
        VERSION_STALE,
        /** First contact has been made — the window is shut and only a manager can reopen it. */
        ALREADY_CONTACTED,
        /** The lead reached CONVERTED or LOST while the alert was on screen. */
        TERMINAL_STAGE,
        /** Locked lead expected (reassign / reopen) but the lead is still open. */
        NOT_LOCKED
    }

    private final Reason reason;
    private final UUID leadPublicId;
    private final String currentOwnerName;
    private final UUID currentOwnerPublicId;
    private final int currentClaimVersion;

    public LeadClaimLostException(String message,
                                  Reason reason,
                                  UUID leadPublicId,
                                  String currentOwnerName,
                                  UUID currentOwnerPublicId,
                                  int currentClaimVersion) {
        super(message);
        this.reason = reason;
        this.leadPublicId = leadPublicId;
        this.currentOwnerName = currentOwnerName;
        this.currentOwnerPublicId = currentOwnerPublicId;
        this.currentClaimVersion = currentClaimVersion;
    }
}
