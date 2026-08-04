package com.crm.travelcrm.fleet.dto;

/**
 * One entry of {@code GET /api/fleet/leg-change-reasons} — the handover vocabulary for the swap form.
 *
 * <p>Served rather than hardcoded, for the same reason as {@code FleetExpenseTypeDto} and
 * {@code FleetCashDirectionDto}: the moment the frontend keeps its own copy of an enum, that copy
 * drifts. This one is small enough to feel harmless, which is exactly how the lead module ended up
 * with a stage that never existed in the backend enum.
 *
 * @param code  enum name — the wire value
 * @param label human-readable, for the dropdown
 */
public record FleetLegChangeReasonDto(String code, String label) {
}
