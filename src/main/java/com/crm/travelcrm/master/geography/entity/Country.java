package com.crm.travelcrm.master.geography.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Top of the geography hierarchy: {@code Country → Destination → City}.
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

    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<Destination> destinations = new ArrayList<>();
}