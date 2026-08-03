package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetLegChangeReason;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One vehicle + driver span within a trip. A trip is a sequence of legs.
 *
 * <p><b>Why this exists.</b> {@code FleetTrip.vehicle} and {@code .driver} are
 * {@code @ManyToOne(optional = false)} — single-valued. A breakdown 200 km into a Nepal run
 * therefore had exactly two representations: overwrite the vehicle and corrupt the trip, or cancel
 * and re-create and lose the odometer chain. Mid-trip substitution is the most common exception in
 * fleet operations, and neither option is acceptable.
 *
 * <p><b>Odometers live here, not on the trip.</b> This is the part that is easy to get wrong. Trip-level
 * {@code endOdometer - startOdometer} is meaningless the moment two vehicles are involved: leg 1 on
 * a vehicle reading 45,000→45,200 followed by leg 2 on a vehicle reading 88,000→88,300 is a 500 km
 * trip, but the trip-level subtraction reports 43,300 km — and that number is the denominator of the
 * cost-per-km report. Swap the vehicles the other way and the trip can never be closed at all,
 * because the end reading is below the start; its settlement then never opens and the driver's cash
 * is stuck forever. So the range check is per leg, against that leg's own vehicle, and
 * {@code trip.distanceKm} is the SUM of its legs.
 *
 * <p><b>Legs are also how a late challan finds its driver.</b> A challan arriving six weeks later
 * asks "who was on this vehicle at 14:20 on the 9th". The answer is the leg whose
 * {@code [startDatetime, endDatetime)} covers that instant — half-open, so a handover instant belongs
 * to exactly one leg and never to both.
 *
 * <p>Every existing trip migrates to exactly one leg carrying its current vehicle, driver, odometers
 * and times, with a null {@link #changeReason} — nothing changed, that is simply how it started.
 */
@Entity
@Table(name = "fleet_trip_legs", indexes = {
        @Index(name = "idx_fleet_leg_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_leg_trip", columnList = "trip_id,seq"),
        // Drives the "which leg covers this instant" lookup for late-arriving challans and tolls.
        @Index(name = "idx_fleet_leg_vehicle_window", columnList = "tenant_id,vehicle_id,start_datetime"),
        @Index(name = "idx_fleet_leg_driver_window", columnList = "tenant_id,driver_id,start_datetime")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetTripLeg extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_leg_trip"))
    private FleetTrip trip;

    /** 1-based position within the trip. Unique per trip (enforced by index in SQL). */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_leg_vehicle"))
    private FleetVehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_leg_driver"))
    private FleetDriver driver;

    @Column(name = "start_datetime", nullable = false)
    private LocalDateTime startDatetime;

    /** Null while this leg is the running one. Half-open interval: the end instant belongs to the NEXT leg. */
    @Column(name = "end_datetime")
    private LocalDateTime endDatetime;

    @Column(name = "start_odometer")
    private Integer startOdometer;

    @Column(name = "end_odometer")
    private Integer endOdometer;

    /** {@code endOdometer - startOdometer} for THIS vehicle. Never spans a vehicle change. */
    @Column(name = "distance_km")
    private Integer distanceKm;

    /**
     * Null on the first leg — nothing changed, the trip simply began. Required on every subsequent
     * leg: a vehicle or driver swap without a recorded reason is unauditable, and this is the field
     * an owner reads when a trip cost twice what it should have.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_reason", length = 20)
    private FleetLegChangeReason changeReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Recomputes this leg's own distance. Null unless BOTH readings for this vehicle are known. */
    public void computeDistance() {
        distanceKm = (startOdometer != null && endOdometer != null)
                ? endOdometer - startOdometer
                : null;
    }

    /** True when {@code instant} falls in {@code [start, end)} — half-open, so handovers are unambiguous. */
    public boolean covers(LocalDateTime instant) {
        if (instant == null || startDatetime == null) return false;
        if (instant.isBefore(startDatetime)) return false;
        return endDatetime == null || instant.isBefore(endDatetime);
    }
}
