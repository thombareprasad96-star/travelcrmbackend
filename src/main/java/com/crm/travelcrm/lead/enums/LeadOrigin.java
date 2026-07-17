package com.crm.travelcrm.lead.enums;

/**
 * How a lead entered the system — as distinct from {@link LeadSource}, which says <em>where</em> it
 * came from.
 *
 * <p><b>Why both exist.</b> {@code GOOGLE_ADS} and {@code WHATSAPP} are deliberately both manually
 * selectable and machine-stamped, so {@code leadSource} alone cannot answer "did a human type this
 * or did an integration assert it?". Without that distinction, source-wise conversion reporting
 * silently mixes machine-verified provenance with a human's guess. This enum is the whole reason
 * that decision is safe.
 *
 * <p>Stored as {@code @Enumerated(STRING)}, so adding a constant needs a matching
 * {@code leads_origin_check} refresh block in {@code db/indexes.sql} — Hibernate generates the
 * constraint when it first creates the column and {@code ddl-auto=update} never alters it again.
 * {@code SchemaEnumConstraintValidator} will refuse to start if the two drift.
 */
public enum LeadOrigin {

    /** A human typed it into the CRM. The default for every lead that predates this column. */
    MANUAL,

    /** A {@code LeadSourceIntegration} produced it — the row's id is on {@code sourceIntegrationId}. */
    INTEGRATION,

    /**
     * Machine-made, but with no integration row behind it: the sub-agent portal, repeat-customer
     * automation. A null {@code sourceIntegrationId} is therefore NOT a reliable "a human made this"
     * test — check {@code origin} instead.
     */
    SYSTEM
}