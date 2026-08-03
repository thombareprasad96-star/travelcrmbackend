package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetLegChangeReason;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One vehicle + driver span within a trip.
 *
 * <p>A single-leg trip is the normal case and reads exactly like the trip itself. More than one leg
 * means the duty changed hands — a breakdown, a relief driver, a reallocation — and this is the only
 * place that history survives: the trip's own vehicle and driver fields point at the CURRENT leg, so
 * without these rows the earlier vehicle, its odometer span and who was driving it are simply gone.
 *
 * @param seq          1-based position in the trip
 * @param changeReason null on the first leg — nothing changed, that is how the trip began
 * @param distanceKm   this leg's own distance. The trip's total is the SUM of these, never
 *                     end-minus-start across a vehicle change
 */
public record FleetTripLegDto(
        UUID publicId,
        int seq,
        UUID vehiclePublicId,
        String vehicleNumber,
        UUID driverPublicId,
        String driverName,
        LocalDateTime startDatetime,
        LocalDateTime endDatetime,
        Integer startOdometer,
        Integer endOdometer,
        Integer distanceKm,
        FleetLegChangeReason changeReason,
        String changeReasonLabel,
        String notes
) {
}
