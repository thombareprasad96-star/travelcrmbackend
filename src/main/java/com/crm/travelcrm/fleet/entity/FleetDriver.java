package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * A driver in the tenant's fleet. Drivers are NOT staff {@code User}s — they have no login;
 * this is purely an operational record (licence expiry feeds the document-expiry scan).
 */
@Entity
@Table(name = "fleet_drivers", indexes = {
        @Index(name = "idx_fleet_driver_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_driver_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetDriver extends BaseTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "license_number", length = 40)
    private String licenseNumber;

    @Column(name = "license_expiry")
    private LocalDate licenseExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private FleetDriverStatus status = FleetDriverStatus.ACTIVE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}