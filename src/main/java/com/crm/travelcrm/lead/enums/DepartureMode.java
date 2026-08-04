package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the traveller leaves on the outbound leg. This is the DISCRIMINATOR that gates the whole
 * transport sub-form on the lead and booking screens: pick "Flight / Airport" and the airport /
 * code / preferred-time trio applies, "Train / Rail" the station trio, "Car / Road" the pickup trio.
 *
 * <p>The wire format is the {@code displayName} exactly as the frontend sends it
 * ({@code "Flight / Airport"}, spaces around the slash included) — see {@code DEPARTURE_MODES} in
 * {@code CreateLead.jsx} and {@code MODE_FIELDS}, which keys its per-mode field lists off the same
 * strings. Change one side without the other and the discriminator silently stops round-tripping:
 * the lead saves, then reopens with the transport section unset and every field under it orphaned.
 *
 * <p>{@code fromValue} returns {@code null} on blank rather than throwing, so an untouched optional
 * dropdown does not blow up deserialization before bean validation gets a chance to run.
 */
public enum DepartureMode {

    FLIGHT("Flight / Airport"),
    TRAIN("Train / Rail"),
    CAR("Car / Road"),
    BUS("Bus"),
    OTHER("Other");

    private final String displayName;

    DepartureMode(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static DepartureMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;   // optional field — an untouched dropdown is not an error
        }
        for (DepartureMode mode : values()) {
            if (mode.displayName.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown departure mode: " + value);
    }
}
