package com.crm.travelcrm.master.vehicle;

import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vehicle_id")
    private Long id;

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

    // null = global vehicle (platform-managed, visible to all tenants);
    // non-null = owned by that tenant only.
    // No DB-level FK — cross-aggregate reference to tenants.id, enforced at the application layer.
    @Column(name = "tenant_id")
    private Long tenantId;

    // Optional city association for hierarchy: Country → Destination → City → Vehicle
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id",
            foreignKey = @ForeignKey(name = "fk_vehicle_city"))
    private City city;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}