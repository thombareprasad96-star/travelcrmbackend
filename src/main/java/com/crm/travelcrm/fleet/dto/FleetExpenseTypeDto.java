package com.crm.travelcrm.fleet.dto;

import java.util.List;

/**
 * One entry of {@code GET /api/fleet/expense-types} — the category catalogue plus the metadata the
 * entry form needs to render itself.
 *
 * <p><b>Why the server owns this.</b> Every alternative ends with the frontend keeping its own copy
 * of the enum vocabulary, and that copy drifts. The lead module is the cautionary tale in this very
 * codebase: its stage strings are duplicated across four frontend files with three different
 * memberships, one of which contains a phantom value that never existed in the backend enum, while
 * another silently rewrites a lead's source on save because its hardcoded list is missing an option.
 * A dropdown fed from the server cannot do that.
 *
 * @param code           enum name — the wire value, stored and reported on
 * @param label          human-readable, for the dropdown
 * @param requiredFields extra fields this category demands, so the form asks for them and the
 *                       server's validation is never a surprise
 * @param fixedCountry   ISO code the category implies (Nepal types are NP), or null
 * @param gstLikely      whether to show the tax block by default
 * @param systemComputed true for bata and night halt — the form must NOT offer them, because the
 *                       server derives those from the allowance policy at settlement. A hand-typed
 *                       bata row alongside a computed one double-counts.
 */
public record FleetExpenseTypeDto(
        String code,
        String label,
        List<String> requiredFields,
        String fixedCountry,
        boolean gstLikely,
        boolean systemComputed
) {
}
