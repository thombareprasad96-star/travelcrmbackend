package com.crm.travelcrm.master.vehicle;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
        name = "vehicle_master",
        indexes = {
                @Index(name = "idx_vehicles_tenant", columnList = "tenant_id"),
                @Index(name = "idx_vehicles_city",   columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// PK is the single inherited BaseEntity.id (@Id @GeneratedValue IDENTITY); the override
// only renames its column to the existing "vehicle_id" so the schema is unchanged.
// Tenant isolation, public_id, audit columns and soft-delete all come from BaseTenantEntity.
@AttributeOverride(name = "id", column = @Column(name = "vehicle_id"))
public class VehicleEntity extends BaseTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    // Optional city association for hierarchy: Country → Destination → City → Vehicle
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id",
            foreignKey = @ForeignKey(name = "fk_vehicle_city"))
    private City city;

    // Global vehicles (tenant_id IS NULL) are platform-managed and visible to all tenants.
    // Derived, not persisted — mirrors the Destination "global" convention.
    @Transient
    public boolean isGlobal() {
        return getTenantId() == null;
    }
}