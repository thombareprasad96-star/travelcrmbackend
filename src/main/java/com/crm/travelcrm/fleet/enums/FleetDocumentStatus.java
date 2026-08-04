package com.crm.travelcrm.fleet.enums;

/**
 * Where a compliance document stands.
 *
 * <p><b>{@link #ACTIVE} / {@link #EXPIRING} / {@link #EXPIRED} are derived from the dates</b>, not
 * typed — a stored status that disagrees with its own {@code validUntil} is worse than no status.
 * They are recomputed on read and by the expiry scan.
 *
 * <p><b>{@link #SUPERSEDED} and {@link #REVOKED} are decisions</b>, and those are stored. A renewal
 * creates a NEW row and marks the old one superseded; it never overwrites, because the previous
 * certificate's number, authority, validity and cost are what an assessing officer asks for years
 * later. That is the whole reason this table replaced four date columns.
 */
public enum FleetDocumentStatus {

    ACTIVE("Valid"),

    /** Inside the warning window — still legal, but the renewal needs starting. */
    EXPIRING("Expiring"),

    EXPIRED("Expired"),

    /** Replaced by a renewal. Kept in full: this is the history, not a deleted row. */
    SUPERSEDED("Superseded"),

    /** Cancelled by the issuing authority, or by the operator, with a recorded reason. */
    REVOKED("Revoked");

    private final String label;

    FleetDocumentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Is this the row that currently represents the vehicle's or driver's standing? */
    public boolean isCurrent() {
        return this == ACTIVE || this == EXPIRING || this == EXPIRED;
    }

    /** Does this row fail a compliance check, subject to the category's blocking rule? */
    public boolean isFailing() {
        return this == EXPIRED || this == REVOKED;
    }
}
