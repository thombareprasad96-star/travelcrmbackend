package com.crm.travelcrm.master.cruise;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "cruises",
        indexes = {
                @Index(name = "idx_cruise_tenant", columnList = "tenant_id"),
                @Index(name = "idx_cruise_city",   columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Cruise extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id",
            foreignKey = @ForeignKey(name = "fk_cruise_city"))
    private City city;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image", length = 500)
    private String image;

    @OneToMany(mappedBy = "cruise", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CruiseRoomType> roomTypes = new ArrayList<>();
}