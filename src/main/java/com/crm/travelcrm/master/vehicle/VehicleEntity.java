package com.crm.travelcrm.master.vehicle;

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
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}