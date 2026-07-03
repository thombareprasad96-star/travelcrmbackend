package com.crm.travelcrm.fleet.enums;

/**
 * Employment state of a driver. "On trip" is NOT a stored status — it is derived from
 * the existence of an ONGOING trip and exposed as a computed flag in the response DTO.
 */
public enum FleetDriverStatus {
    ACTIVE,
    INACTIVE
}