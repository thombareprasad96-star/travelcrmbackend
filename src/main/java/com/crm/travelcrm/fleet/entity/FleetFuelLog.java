package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One fuel fill-up. Saving one bumps the vehicle's {@code lastOdometer} (never downward). */
@Entity
@Table(name = "fleet_fuel_logs", indexes = {
        @Index(name = "idx_fleet_fuel_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_fuel_vehicle", columnList = "vehicle_id"),
        @Index(name = "idx_fleet_fuel_date", columnList = "log_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetFuelLog extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_fuel_vehicle"))
    private FleetVehicle vehicle;

    /** Column named log_date — "date" is a reserved word in some DBs. */
    @Column(name = "log_date", nullable = false)
    private LocalDate date;

    @Column(name = "liters", nullable = false, precision = 8, scale = 2)
    private BigDecimal liters;

    @Column(name = "cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "odometer")
    private Integer odometer;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}