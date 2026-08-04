package com.crm.travelcrm.hotelmarketplace.catalog.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.ConfirmationMode;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A hotel in the SuperAdmin-owned global catalog.
 *
 * <p><b>Extends {@link BaseEntity}, not {@code BaseTenantEntity}, deliberately.</b> The platform owns
 * these rows and reads them across every tenant, so there is no {@code tenant_id} and the Hibernate
 * {@code tenantFilter} does not apply. The consequence is that nothing scopes a query here
 * automatically — tenant-facing reads must go through the marketplace query service, which filters
 * to {@link PlatformHotelStatus#ACTIVE} itself.</p>
 *
 * <p><b>Geography is flat strings, not a FK.</b> {@code City}/{@code Destination}/{@code Country} are
 * all tenant-scoped, so a global row cannot reference them. Resolving this hotel onto one particular
 * tenant's geography is the projection service's job, and it is allowed to fail loudly (see
 * {@code HotelSyncStatus.LOCATION_MAPPING_REQUIRED}) rather than guess.</p>
 *
 * <p>Descriptive data only. Rates, availability and commercial terms live in their own aggregates so
 * that editing a hotel's name can never move a price someone has already been quoted.</p>
 */
@Entity
@Table(
        name = "platform_hotels",
        indexes = {
                @Index(name = "idx_platform_hotel_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformHotel extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PlatformHotelStatus status = PlatformHotelStatus.DRAFT;

    // ── Location (flat, tenant-independent) ─────────────────────────────────

    /** ISO country code. The sync matches a tenant Country on this, never on display name. */
    @Column(name = "country_code", length = 3)
    private String countryCode;

    @Column(name = "state_name", length = 120)
    private String stateName;

    @Column(name = "city_name", nullable = false, length = 120)
    private String cityName;

    /** Optional stable city/airport code, when the operator has one. */
    @Column(name = "city_code", length = 20)
    private String cityCode;

    @Column(length = 500)
    private String address;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    // ── Descriptive ─────────────────────────────────────────────────────────

    @Column
    private Integer stars;

    @Column
    private Double rating;

    @Column(length = 500)
    private String website;

    @Column(name = "map_url", length = 500)
    private String mapUrl;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @Column(name = "primary_image_url", length = 500)
    private String primaryImageUrl;

    /** Guest-facing contact, reproduced on a voucher. Never a settlement contact. */
    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "platform_hotel_amenities",
            joinColumns = @JoinColumn(name = "platform_hotel_id"))
    @Column(name = "amenity", length = 200)
    @Builder.Default
    private List<String> amenities = new ArrayList<>();

    // ── Commercial / operational ────────────────────────────────────────────

    /** Logical link to a supplier. No FK — vendors are tenant-scoped, this catalog is not. */
    @Column(name = "supplier_vendor_public_id")
    private UUID supplierVendorPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_mode", nullable = false, length = 30)
    @Builder.Default
    private ConfirmationMode confirmationMode = ConfirmationMode.SUPERADMIN_APPROVAL;

    /**
     * Incremented on every sync-relevant descriptive change. A tenant projection whose stored version
     * is behind this is STALE and re-syncs on next read. Replaying the same version must be a no-op,
     * which is what makes the sync safe to retry.
     */
    @Column(name = "catalog_version", nullable = false)
    @Builder.Default
    private Long catalogVersion = 1L;

    // ── Children ────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PlatformHotelRoom> rooms = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PlatformHotelMealPlan> mealPlans = new ArrayList<>();

    /** Bump the catalog version — call from every mutation that changes what a tenant would see. */
    public void bumpCatalogVersion() {
        this.catalogVersion = (this.catalogVersion == null ? 0L : this.catalogVersion) + 1L;
    }
}
