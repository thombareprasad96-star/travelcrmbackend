package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.entity.FleetTripLeg;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetLegChangeReason;
import com.crm.travelcrm.fleet.repository.FleetTripLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps a trip's legs in step with its lifecycle.
 *
 * <p><b>Why this is not optional plumbing.</b> The legs table shipped with a backfill, so every
 * historical trip has one — but nothing was creating a leg for a NEW trip. Two things silently
 * broke as a result: an expense could never resolve which leg (and therefore which driver) it
 * belonged to, and the settlement's allowance is computed from the days in a driver's OWN legs, so
 * every new trip paid a bata of exactly zero. Both fail quietly, which is the worst way to fail.
 *
 * <p><b>Odometers live on the leg, and this is the part that is easy to get wrong.</b> Trip-level
 * {@code end − start} is meaningless the moment two vehicles are involved: a leg on a vehicle
 * reading 45,000→45,200 followed by a leg on one reading 88,000→88,300 is a 500 km trip, but the
 * trip-level subtraction says 43,300 — and that number is the denominator of cost-per-km. Swap the
 * vehicles the other way and the trip can never be closed at all, because the end reading is below
 * the start; its settlement then never opens and the driver's cash is stuck for good.
 *
 * <p>So: each leg validates against its OWN vehicle's readings, and {@code trip.distanceKm} is the
 * sum of its legs. With a single leg that is arithmetically identical to the old behaviour, which is
 * what keeps every existing trip untouched.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FleetTripLegManager {

    private final FleetTripLegRepository legRepository;

    /**
     * Opens leg 1 for a newly created trip. A post-facto entry (created already COMPLETED) gets its
     * leg closed in the same breath, so the diary row and its leg always agree.
     */
    public FleetTripLeg openFirstLeg(FleetTrip trip) {
        FleetTripLeg leg = FleetTripLeg.builder()
                .trip(trip)
                .seq(1)
                .vehicle(trip.getVehicle())
                .driver(trip.getDriver())
                .startDatetime(trip.getStartDatetime())
                .endDatetime(trip.getEndDatetime())
                .startOdometer(trip.getStartOdometer())
                .endOdometer(trip.getEndOdometer())
                // Null by design on the first leg: nothing changed, this is simply how the trip began.
                .changeReason(null)
                .build();
        leg.computeDistance();
        return legRepository.save(leg);
    }

    /** Every leg of a trip, in order. */
    public List<FleetTripLeg> legsOf(FleetTrip trip) {
        return legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId());
    }

    /** The running leg — the one with no end. */
    public FleetTripLeg currentLeg(FleetTrip trip) {
        return legRepository
                .findFirstByTrip_IdAndEndDatetimeIsNullAndDeletedAtIsNullOrderBySeqDesc(trip.getId())
                .orElseGet(() -> lastLeg(trip));
    }

    private FleetTripLeg lastLeg(FleetTrip trip) {
        List<FleetTripLeg> legs = legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId());
        return legs.isEmpty() ? null : legs.get(legs.size() - 1);
    }

    /** Mirrors a start onto the open leg. */
    public void applyStart(FleetTrip trip, Integer startOdometer, LocalDateTime startAt) {
        FleetTripLeg leg = currentLeg(trip);
        if (leg == null) { openFirstLeg(trip); return; }
        leg.setStartOdometer(startOdometer);
        leg.setStartDatetime(startAt);
        leg.computeDistance();
        legRepository.save(leg);
    }

    /**
     * Closes the open leg and returns the trip's total distance across ALL legs.
     *
     * @return summed distance, or null when no leg has both readings — the same "unknown" the trip
     *         has always used, rather than a misleading zero
     */
    public Integer applyClose(FleetTrip trip, Integer endOdometer, LocalDateTime endAt) {
        FleetTripLeg leg = currentLeg(trip);
        if (leg != null) {
            leg.setEndOdometer(endOdometer);
            leg.setEndDatetime(endAt);
            leg.computeDistance();
            legRepository.save(leg);
        }
        return totalDistance(trip);
    }

    /**
     * Odometer floor for a close: the CURRENT leg's start, not the trip's.
     *
     * <p>On a substituted trip the trip-level start belongs to a different vehicle entirely, so
     * validating against it either rejects a perfectly good reading or accepts a nonsensical one.
     */
    public Integer closeFloor(FleetTrip trip) {
        FleetTripLeg leg = currentLeg(trip);
        return leg != null ? leg.getStartOdometer() : trip.getStartOdometer();
    }

    /** Sum over legs; null when nothing is measurable yet. */
    public Integer totalDistance(FleetTrip trip) {
        List<FleetTripLeg> legs = legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId());
        Integer total = null;
        for (FleetTripLeg l : legs) {
            if (l.getDistanceKm() == null) continue;
            total = (total == null ? 0 : total) + l.getDistanceKm();
        }
        return total;
    }

    /**
     * Keeps leg 1 aligned when the vehicle or driver is reassigned while the trip is still PLANNED.
     * This is a correction, not a substitution — nothing has happened yet, so no new leg is created.
     */
    public void syncPlannedAssignment(FleetTrip trip) {
        List<FleetTripLeg> legs = legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId());
        if (legs.size() != 1) return;      // a substituted trip is never silently rewritten
        FleetTripLeg leg = legs.get(0);
        leg.setVehicle(trip.getVehicle());
        leg.setDriver(trip.getDriver());
        leg.setStartDatetime(trip.getStartDatetime());
        legRepository.save(leg);
    }

    /**
     * Mid-trip substitution: closes the running leg on the old vehicle and opens the next one.
     *
     * <p>This is the exception legs exist for — a breakdown 200 km into a Nepal run, a driver
     * handover, a relief driver after duty hours. Before legs, an operator had two options: overwrite
     * the trip's vehicle and corrupt it, or cancel and re-create and lose the odometer chain.
     *
     * @param atOdometer reading on the OUTGOING vehicle when it stopped
     * @param newStartOdometer reading on the INCOMING vehicle when it took over
     */
    public FleetTripLeg swap(FleetTrip trip, FleetVehicle newVehicle, FleetDriver newDriver,
                             FleetLegChangeReason reason, Integer atOdometer,
                             Integer newStartOdometer, LocalDateTime at) {

        if (reason == null) {
            throw new BusinessException(
                    "Say why the vehicle or driver changed — a swap with no reason is unauditable, and "
                            + "it is the field an owner reads when a trip cost twice what it should have",
                    HttpStatus.BAD_REQUEST);
        }
        FleetTripLeg open = currentLeg(trip);
        if (open == null) {
            throw new BusinessException("This trip has no open leg to hand over", HttpStatus.CONFLICT);
        }
        if (open.getStartOdometer() != null && atOdometer != null && atOdometer < open.getStartOdometer()) {
            throw new BusinessException(
                    "Closing reading is below this leg's start reading", HttpStatus.BAD_REQUEST);
        }
        if (open.getStartDatetime() != null && !at.isAfter(open.getStartDatetime())) {
            throw new BusinessException(
                    "The handover time must be after this leg started", HttpStatus.BAD_REQUEST);
        }

        // Half-open interval: the handover instant belongs to the NEW leg only, so a challan or toll
        // timestamped exactly then resolves to exactly one driver.
        open.setEndOdometer(atOdometer);
        open.setEndDatetime(at);
        open.computeDistance();
        legRepository.save(open);

        FleetTripLeg next = FleetTripLeg.builder()
                .trip(trip)
                .seq(open.getSeq() + 1)
                .vehicle(newVehicle)
                .driver(newDriver)
                .startDatetime(at)
                .startOdometer(newStartOdometer)
                .changeReason(reason)
                .build();
        FleetTripLeg saved = legRepository.save(next);

        log.info("Fleet trip leg swap | trip: {} | leg {}→{} | vehicle: {} | driver: {} | reason: {}",
                trip.getId(), open.getSeq(), saved.getSeq(),
                newVehicle.getVehicleNumber(), newDriver.getName(), reason);
        return saved;
    }
}
