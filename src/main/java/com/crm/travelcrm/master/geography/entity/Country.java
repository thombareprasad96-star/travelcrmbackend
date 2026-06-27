package com.crm.travelcrm.master.geography.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Top of the geography hierarchy.
 *
 * <p>A country is the parent of both destinations and cities:</p>
 * <pre>
 * Country ─┬─&gt; Destination ──&gt; City   (city optionally linked to a destination)
 *          └─&gt; City                    (city directly under the country)
 * </pre>
 *
 * <p>Extends {@link BaseTenantEntity}, so the primary key is the inherited
 * {@code id} (the spec's "countryId") and {@code publicId} is what APIs expose.
 * Uniqueness of {@code name} and {@code code} is scoped per tenant. Children
 * cascade fully and are orphan-removed, matching the {@code ON DELETE CASCADE}
 * schema intent (master data uses hard deletes, not the soft-delete flag).</p>
 */
@Entity
@Table(
        name = "countries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_country_tenant_name", columnNames = {"tenant_id", "name"}),
                @UniqueConstraint(name = "uk_country_tenant_code", columnNames = {"tenant_id", "code"})
        },
        indexes = {
                @Index(name = "idx_country_tenant", columnList = "tenant_id"),
                @Index(name = "idx_country_code",   columnList = "tenant_id,code")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Hide trashed rows from every read (see softDeleteFilter on BaseTenantEntity).
@Filter(name = "softDeleteFilter", condition = "deleted_at is null")
public class Country extends BaseTenantEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** 2-char ISO country code, upper-cased by the service. */
    @Column(name = "code", nullable = false, length = 2)
    private String code;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Flag emoji or image URL. */
    @Column(name = "flag", length = 500)
    private String flag;

    @Column(name = "timezone", length = 64)
    private String timezone;

    // Read-only back-reference (no cascade / orphanRemoval). Under the soft-delete model a country
    // is never hard-cascaded into its children: MasterReferenceGuard blocks trashing a country that
    // still has active destinations/cities, and the Trash purge removes children-before-parents on
    // their own retention clock. A destructive cascade here would let the country purge silently
    // hard-delete a child that had been restored under a still-trashed country.
    @OneToMany(mappedBy = "country", fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<Destination> destinations = new ArrayList<>();

    /** Cities that belong directly to this country. Read-only back-reference (see above). */
    @OneToMany(mappedBy = "country", fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<City> cities = new ArrayList<>();
}