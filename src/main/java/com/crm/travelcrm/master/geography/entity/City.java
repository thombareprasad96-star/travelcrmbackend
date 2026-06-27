package com.crm.travelcrm.master.geography.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 *
 *
 * A city in the geography model.
 *
 * <p>Relationships (per the master-data spec):</p>
 * <ul>
 *   <li><b>Country</b> — a city <i>always</i> belongs to exactly one country
 *       ({@code country_id}, NOT NULL).</li>
 *   <li><b>Destination</b> — a city <i>may optionally</i> belong to a destination
 *       ({@code destination_id}, nullable). When present, the destination's country
 *       must match the city's country (enforced in the service layer).</li>
 * </ul>
 *
 * <p>PK is the inherited {@code id} (the spec's "cityId"); the pair
 * {@code (tenant_id, country_id, name)} is unique per tenant — a city name is unique
 * within its country.</p>
 */
@Entity
@Table(
        name = "cities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_city_tenant_country_name",
                        columnNames = {"tenant_id", "country_id", "name"}
                )
        },
        indexes = {
                @Index(name = "idx_city_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_city_country",     columnList = "country_id"),
                @Index(name = "idx_city_destination", columnList = "destination_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Hide trashed rows from every read (see softDeleteFilter on BaseTenantEntity).
@Filter(name = "softDeleteFilter", condition = "deleted_at is null")
public class City extends BaseTenantEntity {

    /** Required parent — every city belongs to exactly one country. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "country_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_city_country")
    )
    private Country country;

    /**
     * Optional parent — a city may belong to a destination. Null when the city is
     * created directly under a country (flat City master page) and not yet linked
     * to any destination. The destination's country must equal {@link #country}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "destination_id",
            nullable = true,
            foreignKey = @ForeignKey(name = "fk_city_destination")
    )
    private Destination destination;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

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
    private String imagePath;

    @Column(name = "days_to_stay")
    private Integer daysToStay;
}