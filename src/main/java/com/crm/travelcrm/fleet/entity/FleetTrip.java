package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One diary trip. Vehicle/driver are REAL in-module FKs (same bounded context — the
 * Sightseeing→City precedent), so list views join names instead of snapshotting them.
 * The booking link stays a cross-aggregate logical FK (no DB constraint) with publicId +
 * code snapshots for display, same pattern as {@code Reminder.leadRefId/leadName}.
 */
@Entity
@Table(name = "fleet_trips", indexes = {
        @Index(name = "idx_fleet_trip_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_trip_status", columnList = "status"),
        @Index(name = "idx_fleet_trip_vehicle", columnList = "vehicle_id"),
        @Index(name = "idx_fleet_trip_driver", columnList = "driver_id"),
        @Index(name = "idx_fleet_trip_start", columnList = "start_datetime")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetTrip extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_trip_vehicle"))
    private FleetVehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_trip_driver"))
    private FleetDriver driver;

    // Cross-aggregate logical FK to bookings.id — validated tenant-scoped in the service.
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "booking_public_id")
    private UUID bookingPublicId;

    /** Display snapshot of the booking code (e.g. "BK-00123") — avoids a cross-module join. */
    @Column(name = "booking_code", length = 40)
    private String bookingCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private FleetTripStatus status = FleetTripStatus.PLANNED;

    /** Planned start when PLANNED; actual start once ONGOING/COMPLETED. */
    @Column(name = "start_datetime", nullable = false)
    private LocalDateTime startDatetime;

    /** Planned return when PLANNED (optional); actual end once COMPLETED. */
    @Column(name = "end_datetime")
    private LocalDateTime endDatetime;

    @Column(name = "start_odometer")
    private Integer startOdometer;

    @Column(name = "end_odometer")
    private Integer endOdometer;

    /** endOdometer − startOdometer, persisted whenever both readings are known. */
    @Column(name = "distance_km")
    private Integer distanceKm;

    @Column(name = "route_from", length = 150)
    private String routeFrom;

    @Column(name = "route_to", length = 150)
    private String routeTo;

    @Column(name = "purpose", length = 150)
    private String purpose;

    @Column(name = "fuel_cost", precision = 12, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "toll_cost", precision = 12, scale = 2)
    private BigDecimal tollCost;

    @Column(name = "driver_allowance", precision = 12, scale = 2)
    private BigDecimal driverAllowance;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    // ── Cross-border money ──────────────────────────────────────────────────
    // The office sets ONE rate for the whole trip, once, and every foreign-currency cost row on it
    // inherits that rate at write time. This is why no client ever sends an exchange rate: a driver
    // standing at the Sunauli border with a Bhansar receipt in NPR types NPR, and never sees, knows
    // or influences the conversion. The rate is frozen onto each row as it is written, so a later
    // edit here can never silently restate costs that were already recorded and reported.

    /** Foreign currency in use on this trip, e.g. NPR. Null for a purely domestic trip. */
    @Column(name = "fx_currency", length = 3)
    private String fxCurrency;

    /**
     * Units of base currency per one {@link #fxCurrency}. {@code numeric(18,8)} deliberately — at
     * {@code numeric(14,2)} a rate of 0.625 stores as 0.63, and NPR 100,000 of Bhansar converts to
     * Rs 63,000 instead of Rs 62,500.
     */
    @Column(name = "fx_rate", precision = 18, scale = 8)
    private BigDecimal fxRate;
}