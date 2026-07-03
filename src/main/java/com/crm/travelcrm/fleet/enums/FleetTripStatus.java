package com.crm.travelcrm.fleet.enums;

/**
 * Trip lifecycle: {@code PLANNED → ONGOING → COMPLETED} (or {@code CANCELLED} from
 * PLANNED/ONGOING). A trip created with an end datetime is a post-facto diary entry and
 * lands directly in {@code COMPLETED}; {@code ONGOING} is only ever entered via the
 * start endpoint, which also flips the vehicle to ON_TRIP.
 */
public enum FleetTripStatus {
    PLANNED,
    ONGOING,
    COMPLETED,
    CANCELLED
}