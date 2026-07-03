package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A completed service/repair record. The latest log per vehicle (by serviceDate) is the
 * source of the vehicle's denormalized {@code nextServiceDueDate}/{@code nextServiceDueKm},
 * recomputed by the service on every log create/update/delete. Logging a service does NOT
 * flip the vehicle to MAINTENANCE — that status is set manually while work is in progress.
 */
@Entity
@Table(name = "fleet_maintenance_logs", indexes = {
        @Index(name = "idx_fleet_maint_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_maint_vehicle", columnList = "vehicle_id"),
        @Index(name = "idx_fleet_maint_date", columnList = "service_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetMaintenanceLog extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_maint_vehicle"))
    private FleetVehicle vehicle;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    /** e.g. "Oil change", "Tyre replacement", "General service". */
    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType;

    @Column(name = "cost", precision = 12, scale = 2)
    private BigDecimal cost;

    /** Free-text garage/workshop name per spec — intentionally NOT a Vendor FK. */
    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "odometer")
    private Integer odometer;

    @Column(name = "next_service_due_date")
    private LocalDate nextServiceDueDate;

    @Column(name = "next_service_due_km")
    private Integer nextServiceDueKm;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}