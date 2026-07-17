package com.crm.travelcrm.lead.dto;

/**
 * One entry in the lead-source catalog served by {@code GET /api/leads/meta/sources}.
 *
 * <p>Derived from {@code LeadSource.values()} at request time — there is no hand-maintained list
 * here, which is the entire point: the frontend's hardcoded copy is the bug this endpoint kills.
 */
public record LeadSourceOptionDTO(

        /**
         * {@code LeadSource.getDisplayName()} — "Google Ads". <b>THE WIRE VALUE. Not {@code name()}.
         * Do not "fix" this.</b>
         *
         * <p>{@code @JsonValue} sits on {@code getDisplayName()}, so {@code GET /api/leads} emits
         * {@code leadSource: "Google Ads"} and the edit form seeds that exact string. An
         * {@code <option value="GOOGLE_ADS">} matches nothing, the select renders blank, and the
         * user "fixes" a field that was already correct — the precise failure this endpoint exists
         * to prevent. The POST direction masks it: {@code @JsonCreator fromValue} accepts both
         * vocabularies case-insensitively, so a {@code name()}-based value round-trips fine on save
         * and the bug shows up only as a blank dropdown.
         */
        String value,

        /**
         * Identical to {@link #value} <b>on purpose</b> — the wire vocabulary IS the display name.
         * This is truth, not redundancy. Kept as its own field so the frontend never has to know
         * that, and so a future divergence has somewhere to go.
         */
        String label,

        /**
         * {@code LeadSource.name()} — "GOOGLE_ADS". A stable key for frontend lookups (icon and
         * colour maps, tests) that must survive a displayName being reworded.
         * <b>NEVER an {@code <option value>}</b> — see {@link #value}.
         */
        String code,

        /** {@code SourceSelectability.name()} — MANUAL_SELECTABLE | MACHINE_ONLY | LEGACY_READ_ONLY. */
        String selectability
) {}
