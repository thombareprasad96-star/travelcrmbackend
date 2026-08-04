package com.crm.travelcrm.fleet.integration.spi;

import java.util.UUID;

/**
 * What a fleet trip was run FOR, reduced to the three values the fleet module actually stores.
 *
 * <p>In CRM mode this is a {@code Booking}; in a standalone deployment it is whatever the operator
 * typed. Fleet never sees either type — only this snapshot — which is what lets the same trip code
 * compile and run in both products.
 *
 * @param id       internal id of the source row, or {@code null} when the reference is free text
 * @param publicId public id of the source row, or {@code null} when the reference is free text
 * @param code     human-readable label shown on the trip ("BKG-25-0042", "Sharma family — Char Dham")
 */
public record FleetJobReference(Long id, UUID publicId, String code) {

    /** A reference with no resolvable source row — the standalone case. */
    public static FleetJobReference freeText(String code) {
        return new FleetJobReference(null, null, code);
    }
}
