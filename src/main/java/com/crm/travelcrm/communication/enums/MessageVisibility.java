package com.crm.travelcrm.communication.enums;

/**
 * Who inside the tenant may read a timeline row.
 *
 * <p>A security predicate, not a label — it is applied in the repository query, never in the
 * mapper, so a private note can never reach a DTO belonging to someone not entitled to it.
 *
 * <p>Only {@link #PUBLIC} rows were ever seen by the contact. The other two are internal by
 * construction and are excluded from every customer-facing surface (the traveler portal reads
 * none of this module).
 */
public enum MessageVisibility {

    /** Part of the actual conversation with the contact — every transmitted message is PUBLIC. */
    PUBLIC,

    /** Internal note: visible to any colleague who can see the conversation. */
    INTERNAL,

    /**
     * Private note: visible to its author only, plus holders of {@code COMM_NOTE_PRIVATE_READ}.
     * That permission is in no role default — TENANT_ADMIN reaches it through the resolver bypass.
     */
    PRIVATE
}
