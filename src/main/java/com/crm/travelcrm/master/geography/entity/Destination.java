package com.crm.travelcrm.master.geography.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "destination_master",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_destination_tenant_name",
                        columnNames = {"tenant_id", "name"}
                )
        },
        indexes = {
                @Index(name = "idx_destination_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_destination_country", columnList = "country_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// PK is the single inherited BaseEntity.id (@Id @GeneratedValue IDENTITY); the override
// only renames its column to the existing "destination_id" so the schema is unchanged.
// (Removed the duplicate local @Id field that shadowed the inherited identifier.)
@AttributeOverride(name = "id", column = @Column(name = "destination_id"))
public class Destination extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "country_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_destination_country")
    )
    private Country country;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "type", length = 50)
    private String type;                        // "Domestic" | "International"

    @Column(name = "image_path", length = 500)
    private String imagePath;                   // Cloudinary secure_url

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "inclusions", columnDefinition = "TEXT")
    private String inclusions;

    @Column(name = "exclusions", columnDefinition = "TEXT")
    private String exclusions;

    @Column(name = "payment_policies", columnDefinition = "TEXT")
    private String paymentPolicies;

    @Column(name = "cancellation_policies", columnDefinition = "TEXT")
    private String cancellationPolicies;

    @Column(name = "booking_terms", columnDefinition = "TEXT")
    private String bookingTerms;

    @Column(name = "status", length = 20)
    private String status;

    /**
     * true  → platform-managed, shared with ALL tenants (read-only for non-platform admins).
     * false → tenant-owned destination.
     */
    @Column(name = "global", nullable = false)
    @Builder.Default
    private boolean global = false;

    /**
     * Cities optionally linked to this destination. Cities are owned by the
     * {@link Country}, not the destination — so this association is a plain inverse
     * with no cascade/orphan-removal. Deleting a destination <b>detaches</b> its
     * cities (sets {@code destination_id = null}); it never deletes them
     * (see {@code DestinationServiceImpl#delete}).
     */
    @OneToMany(mappedBy = "destination", fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<City> cities = new ArrayList<>();
}