package com.crm.travelcrm.master.sightseeing;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
        name = "sightseeings",
        indexes = {
                @Index(name = "idx_sightseeing_tenant", columnList = "tenant_id"),
                @Index(name = "idx_sightseeing_city",   columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Sightseeing extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_sightseeing_city"))
    private City city;

    @Column(nullable = false, length = 300)
    private String title;

    @Column
    private Integer sequence;

    @Column(name = "estimated_hours")
    private Double estimatedHours;

    @Column(name = "suggested_start_time", length = 50)
    private String suggestedStartTime;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String remarks;
}