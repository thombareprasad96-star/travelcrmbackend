package com.crm.travelcrm.master.airline;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
        name = "airlines",
        indexes = {
                @Index(name = "idx_airline_tenant", columnList = "tenant_id"),
                @Index(name = "idx_airline_city",   columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Airline extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id",
            foreignKey = @ForeignKey(name = "fk_airline_city"))
    private City city;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String status;

    @Column(name = "logo", length = 500)
    private String logo;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "fleet", length = 200)
    private String fleet;

    @Column(name = "iata", length = 10)
    private String iata;
}