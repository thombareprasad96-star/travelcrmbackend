package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Operational fleet vehicle (Vehicle Diary) — deliberately separate from the quotation
 * master {@code master/vehicle/VehicleEntity}, which stays untouched. Tenant-scoped via
 * {@link BaseTenantEntity}; soft-deleted via {@code deletedAt} with explicit
 * {@code ...DeletedAtIsNull} finders (Reminder/Vendor mechanism, not the master-data filter).
 */
@Entity
@Table(name = "fleet_vehicles", indexes = {
        @Index(name = "idx_fleet_vehicle_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_vehicle_status", columnList = "status"),
        @Index(name = "idx_fleet_vehicle_number", columnList = "vehicle_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetVehicle extends BaseTenantEntity {

    /** Registration plate — stored trimmed/uppercased; unique per tenant among active rows (service-enforced). */
    @Column(name = "vehicle_number", nullable = false, length = 30)
    private String vehicleNumber;

    /** Free-form category, e.g. "Sedan", "Tempo Traveller", "Bus" — mirrors master vehicle types. */
    @Column(name = "type", length = 60)
    private String type;

    @Column(name = "make", length = 60)
    private String make;

    @Column(name = "model", length = 60)
    private String model;

    /** Manufacturing year — column named model_year ("year" is a reserved word in some DBs). */
    @Column(name = "model_year")
    private Integer year;

    @Column(name = "seating_capacity")
    private Integer seatingCapacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    @Builder.Default
    private FleetOwnerType ownerType = FleetOwnerType.OWN;

    // Cross-module logical FK to vendors.id (no DB constraint) + display snapshots,
    // same pattern as Reminder.assignToUserId/assignToName — avoids the 3-table Vendor
    // join in list views.
    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_public_id")
    private UUID vendorPublicId;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "insurance_expiry")
    private LocalDate insuranceExpiry;

    @Column(name = "rc_expiry")
    private LocalDate rcExpiry;

    @Column(name = "permit_expiry")
    private LocalDate permitExpiry;

    @Column(name = "puc_expiry")
    private LocalDate pucExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private FleetVehicleStatus status = FleetVehicleStatus.AVAILABLE;

    /** Highest odometer reading seen (trip close / fuel / service logs) — only ever bumped upward. */
    @Column(name = "last_odometer")
    private Integer lastOdometer;

    // Denormalized from the LATEST maintenance log (by serviceDate) so the service-due
    // dashboard never scans the log table. Recomputed on every log create/update/delete.
    @Column(name = "next_service_due_date")
    private LocalDate nextServiceDueDate;

    @Column(name = "next_service_due_km")
    private Integer nextServiceDueKm;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Bumps {@code lastOdometer} upward, never down — readings can arrive out of order. */
    public void bumpOdometer(Integer reading) {
        if (reading == null) return;
        if (lastOdometer == null || reading > lastOdometer) {
            lastOdometer = reading;
        }
    }
}