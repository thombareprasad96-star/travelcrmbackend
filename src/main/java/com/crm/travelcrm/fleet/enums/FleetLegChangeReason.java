package com.crm.travelcrm.fleet.enums;

/**
 * Why a trip acquired a new leg — i.e. why the vehicle or driver changed mid-journey.
 *
 * <p>Today {@code FleetTrip.vehicle} and {@code .driver} are single-valued and {@code optional=false},
 * so a breakdown 200 km into a Nepal run has exactly two representations: corrupt the trip by
 * overwriting the vehicle, or cancel and re-create it and lose the odometer chain. Neither is
 * acceptable, and mid-trip substitution is the single most common fleet exception there is.
 *
 * <p>A trip is therefore a sequence of legs. The first leg carries {@code null} here — nothing
 * changed, it is simply how the trip started. Every subsequent leg must say why, because "which
 * driver was on this vehicle at 14:20 on the 9th" is the question a challan arriving six weeks later
 * asks, and the answer has to be in the data.
 */
public enum FleetLegChangeReason {

    /** Vehicle failed on the road; a replacement was sent or arranged locally. */
    BREAKDOWN("Breakdown"),

    /** Planned or unplanned change of driver — relief driver, driver fell ill, end of his rotation. */
    DRIVER_HANDOVER("Driver handover"),

    /** Driver had run out of legal/safe driving hours and was relieved. */
    REST_RULE("Duty-hours / rest"),

    /** Dispatcher or owner reallocated the vehicle to higher-priority work. */
    OWNER_DECISION("Reallocated by office"),

    /** Vehicle was upgraded or downgraded at the customer's request mid-tour. */
    CUSTOMER_REQUEST("Customer request");

    private final String label;

    FleetLegChangeReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
