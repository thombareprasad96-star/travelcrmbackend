package com.crm.travelcrm.master.addon;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Entity
@Table(
        name = "addons",
        indexes = {
                @Index(name = "idx_addon_tenant", columnList = "tenant_id"),
                @Index(name = "idx_addon_city",   columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Addon extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id",
            foreignKey = @ForeignKey(name = "fk_addon_city"))
    private City city;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean active = true;
}