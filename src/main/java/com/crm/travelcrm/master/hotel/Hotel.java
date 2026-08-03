package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "hotels",
        indexes = {
                @Index(name = "idx_hotel_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_hotel_city",    columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Hide trashed rows from every read (see softDeleteFilter on BaseTenantEntity).
@Filter(name = "softDeleteFilter", condition = "deleted_at is null")
public class Hotel extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_hotel_city"))
    private City city;

    @Column(nullable = false, length = 200)
    private String name;

    @Column
    private Integer stars;

    @Column
    private Double rating;

    @Column(length = 500)
    private String address;

    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 500)
    private String website;

    @Column(name = "map_url", length = 500)
    private String mapUrl;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @ElementCollection
    @CollectionTable(name = "hotel_amenities",
            joinColumns = @JoinColumn(name = "hotel_id"))
    @Column(name = "amenity", length = 200)
    @Builder.Default
    private List<String> amenities = new ArrayList<>();

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoomType> roomTypes = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MealPlan> mealPlans = new ArrayList<>();

    // ── Marketplace projection ──────────────────────────────────────────────
    // Set only on a row imported from the platform catalog. A tenant-authored hotel keeps origin
    // TENANT and every other field here null/false, so nothing about the existing master changes.

    /** Who authored this row. Decides whether the descriptive fields are the tenant's to edit. */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    @Builder.Default
    private HotelOrigin origin = HotelOrigin.TENANT;

    /** The catalog hotel this projects. Unique per tenant (partial unique index). */
    @Column(name = "platform_hotel_public_id")
    private UUID platformHotelPublicId;

    /** Catalog version this projection was built from; behind the source ⇒ STALE. */
    @Column(name = "platform_catalog_version")
    private Long platformCatalogVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", length = 30)
    private HotelSyncStatus syncStatus;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** Whether this row may be booked through the marketplace. False for every tenant-authored row. */
    @Column(name = "marketplace_bookable", nullable = false)
    @Builder.Default
    private boolean marketplaceBookable = false;

    /** True when the platform owns this row's descriptive fields. */
    public boolean isPlatformOwned() {
        return origin == HotelOrigin.PLATFORM_SYNC;
    }
}