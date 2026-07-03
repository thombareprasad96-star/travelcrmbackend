package com.crm.travelcrm.fleet.enums;

/**
 * Operational state of a fleet vehicle. {@code ON_TRIP} is managed exclusively by the
 * trip lifecycle (start/close/cancel) and is rejected by the manual status endpoint;
 * {@code MAINTENANCE} / {@code OUT_OF_SERVICE} are set manually.
 */
public enum FleetVehicleStatus {
    AVAILABLE,
    ON_TRIP,
    MAINTENANCE,
    OUT_OF_SERVICE
}