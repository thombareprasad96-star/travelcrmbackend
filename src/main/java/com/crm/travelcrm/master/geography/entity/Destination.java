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
public class Destination extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "destination_id")
    private long id;

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

    @OneToMany(
            mappedBy = "destination",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @BatchSize(size = 50)
    @Builder.Default
    private List<City> cities = new ArrayList<>();
}