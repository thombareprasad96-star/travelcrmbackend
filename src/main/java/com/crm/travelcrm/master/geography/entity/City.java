package com.crm.travelcrm.master.geography.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A city under a {@link Destination}. PK is the inherited {@code id} (the spec's
 * "cityId"); the pair {@code (name, destination)} is unique per tenant.
 *
 * <p>The inverse service collections from the spec ({@code hotels}, {@code vehicles},
 * departing/arriving {@code airlines}, departing/arriving {@code cruises}) are added
 * to this class as each child entity is introduced in its own build batch, so every
 * batch stays independently compilable.</p>
 */
@Entity
@Table(
        name = "cities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_city_tenant_name_destination",
                        columnNames = {"tenant_id", "name", "destination_id"}
                )
        },
        indexes = {
                @Index(name = "idx_city_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_city_destination", columnList = "destination_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class City extends BaseTenantEntity {

    // nullable — cities created via the flat /api/cities endpoint (frontend) may not have a destination yet
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "destination_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_city_destination")
    )
    private Destination destination;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Country name string — populated when city is created via flat endpoint (no destination FK). */
    @Column(name = "country", length = 100)
    private String country;

    /** Airport/city code (e.g. BOM, DEL). */
    @Column(name = "code", length = 10)
    private String code;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "days_to_stay")
    private Integer daysToStay;
}