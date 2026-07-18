package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.quotation.enums.DiscountType;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.enums.TemplateStyle;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root aggregate of a travel quotation. One row per quotation; all the tab data
 * (flights, hotels, sightseeing, cruise, vehicles, add-ons, inclusions/exclusions,
 * pricing) hangs off this entity.
 *
 * <p>Tenant-scoped ({@link BaseTenantEntity}). The link to the originating lead is
 * kept both as the internal {@code leadId} (logical FK, validated tenant-scoped in
 * the service, mirroring {@code Booking.leadId}) and as {@code leadPublicId} (the
 * UUID the API speaks). Customer details are <b>snapshotted</b> from the lead at
 * create/update time so the generated PDF stays stable even if the lead later
 * changes.
 */
@Entity
@Table(
        name = "quotations",
        indexes = {
                @Index(name = "idx_quotation_tenant", columnList = "tenant_id"),
                @Index(name = "idx_quotation_lead_id", columnList = "lead_id"),
                @Index(name = "idx_quotation_lead_public_id", columnList = "lead_public_id"),
                @Index(name = "idx_quotation_stage", columnList = "stage"),
                @Index(name = "idx_quotation_parent", columnList = "parent_quotation_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Quotation extends BaseTenantEntity implements Ownable {

    // Row-level owner: the sub-agent / user who created this quotation (per-user data scoping).
    // Nullable — pre-existing & system rows have none. Stamped on create by OwnershipEntityListener;
    // read by QuotationAccessGuard + list filters (Phase 2). Distinct from created_by (audit email).
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    // ── Lead link (cross-aggregate logical FK) ────────────────────────────────
    @Column(name = "lead_id")
    private Long leadId
            ;                 // internal Lead.id, resolved + validated in service

    @Column(name = "lead_public_id")
    private UUID leadPublicId;           // Lead.publicId — the UUID the frontend sends/reads

    // ── Basic info ────────────────────────────────────────────────────────────
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "version", length = 30)
    private String version;

    /** Per-tenant sequential quote number, shared across all versions of a family. */
    @Column(name = "quote_no")
    private Integer quoteNo;

    // ── Versioning ────────────────────────────────────────────────────────────
    // Every version is a row in this same table. versionNumber increments within a
    // family; parentQuotationId points to the family root (null for the first one).
    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "parent_quotation_id")
    private Long parentQuotationId;

    /**
     * LEGACY. Holds a Cloudinary URL on rows created while new-version PDFs were uploaded there.
     * <b>Nothing writes or serves it any more</b> — quotation PDFs are rendered on demand because the
     * document carries customer PII and a Cloudinary raw asset is public and outlives the row.
     * Kept populated deliberately: it is the only inventory of the assets still sitting in
     * Cloudinary's {@code quotations/} folder, i.e. the list for the one-time cleanup.
     */
    @Column(name = "pdf_url", length = 600)
    private String pdfUrl;

    /**
     * Which design the PDF and the public weblink render in. NULLABLE — every row that predates the
     * column reads as null, and null MEANS {@link TemplateStyle#CLASSIC} (enforced via
     * {@code TemplateStyle.orDefault} at every read). That null-tolerance is the zero-regression
     * guarantee, not an accident; do not make this column NOT NULL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "template_style", length = 20)
    private TemplateStyle templateStyle;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 30)
    private QuotationStage stage;

    /**
     * Snapshot of the originating lead's stage at create/update time. Read-only from the
     * quotation's perspective — the lead pipeline owns it. Refreshed whenever the lead is
     * (re)linked in {@code linkLeadAndSnapshot}, so the builder/list views can surface the
     * lead's pipeline position alongside the quotation's own {@link #stage}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lead_stage", length = 50)
    private LeadStage leadStage;

    /** Optional hero/cover image (Cloudinary URL) rendered at the top of the PDF. */
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Customer snapshot (copied from the lead) ──────────────────────────────
    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Column(name = "destination", length = 300)
    private String destination;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    @Column(name = "adults")
    private Integer adults;

    @Column(name = "children")
    private Integer children;

    @Column(name = "infants")
    private Integer infants;

    // ── Flight section header ─────────────────────────────────────────────────
    @Column(name = "flight_included")
    private Boolean flightIncluded;

    @Column(name = "flight_title", length = 150)
    private String flightTitle;

    @Column(name = "flight_amount", precision = 15, scale = 2)
    private BigDecimal flightAmount;

    @Column(name = "flight_journey", length = 50)
    private String flightJourney;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationFlightSegment> flightSegments = new ArrayList<>();

    // ── Hotel section header ──────────────────────────────────────────────────
    @Column(name = "hotel_included")
    private Boolean hotelIncluded;

    @Column(name = "hotel_title", length = 150)
    private String hotelTitle;

    @Column(name = "hotel_amount", precision = 15, scale = 2)
    private BigDecimal hotelAmount;

    @Column(name = "hotel_notes", columnDefinition = "TEXT")
    private String hotelNotes;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationHotel> hotels = new ArrayList<>();

    // ── Sightseeing section header ────────────────────────────────────────────
    @Column(name = "sightseeing_included")
    private Boolean sightseeingIncluded;

    @Column(name = "sightseeing_title", length = 150)
    private String sightseeingTitle;

    @Column(name = "sightseeing_amount", precision = 15, scale = 2)
    private BigDecimal sightseeingAmount;

    @Column(name = "sightseeing_notes", columnDefinition = "TEXT")
    private String sightseeingNotes;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationSightseeingDay> sightseeingDays = new ArrayList<>();

    // ── Cruise section header ─────────────────────────────────────────────────
    @Column(name = "cruise_included")
    private Boolean cruiseIncluded;

    @Column(name = "cruise_title", length = 150)
    private String cruiseTitle;

    @Column(name = "cruise_amount", precision = 15, scale = 2)
    private BigDecimal cruiseAmount;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationCruise> cruises = new ArrayList<>();

    // ── Vehicle section header ────────────────────────────────────────────────
    @Column(name = "vehicle_included")
    private Boolean vehicleIncluded;

    @Column(name = "vehicle_title", length = 150)
    private String vehicleTitle;

    @Column(name = "vehicle_amount", precision = 15, scale = 2)
    private BigDecimal vehicleAmount;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationVehicle> vehicles = new ArrayList<>();

    // ── Add-on services section header ────────────────────────────────────────
    @Column(name = "addon_included")
    private Boolean addonIncluded;

    @Column(name = "addon_title", length = 150)
    private String addonTitle;

    @Column(name = "addon_amount", precision = 15, scale = 2)
    private BigDecimal addonAmount;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationAddon> addons = new ArrayList<>();

    /**
     * SNAPSHOT of the services the originating lead had selected, as {@code QuotationSection} keys in
     * the order the lead listed them. Taken once when the lead is linked; a later edit to the lead
     * cannot mutate an existing quotation (the lead's own service list is cleared and rewritten
     * wholesale on every lead save, so reading it live would silently re-shape sent quotations).
     *
     * <p><b>Empty means "no lead information" and FAILS OPEN</b> — every section is allowed and the
     * canonical order applies. That covers rows with no lead, legacy rows created before this
     * collection existed, and ingested leads that carry no services.
     *
     * <p>This is an ORDERING/selection hint, never an authorization: the user can always tick
     * "Include X in Quotation" on a service the lead did not ask for, and that choice is honoured.
     * Persisted (rather than re-derived from the lead) because the public share-link has no auth and
     * cannot read the lead at all.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_allowed_services", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "service", length = 50)
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> allowedServices = new ArrayList<>();

    // ── Inclusions / Exclusions / Policies / Terms ────────────────────────────
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_inclusions", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> inclusions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_exclusions", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> exclusions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_payment_policies", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> paymentPolicies = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_cancellation_policies", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> cancellationPolicies = new ArrayList<>();

    /**
     * The STRUCTURED (computable) cancellation policy this quotation was priced under — the publicId
     * of a {@code CancellationPolicy} version. Distinct from the free-text {@link #cancellationPolicies}
     * bullets above, which are display-only. When a booking is converted from this quotation, this
     * exact version is pinned onto the booking so the customer is charged under the terms they were
     * quoted. Null → the booking falls back to the tenant's company default. Optional; set when the
     * quotation is built from a package/template that carries a policy.
     */
    @Column(name = "cancellation_policy_public_id")
    private java.util.UUID cancellationPolicyPublicId;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_booking_terms", joinColumns = @JoinColumn(name = "quotation_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> bookingTerms = new ArrayList<>();

    // ── Pricing / adjustments ─────────────────────────────────────────────────
    @Column(name = "discount", precision = 15, scale = 2)
    private BigDecimal discount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 20)
    private DiscountType discountType;

    @Column(name = "tax", precision = 7, scale = 3)
    private BigDecimal tax;

    @Column(name = "markup", precision = 15, scale = 2)
    private BigDecimal markup;

    // ── Bi-directional wiring helpers ─────────────────────────────────────────
    public void addFlightSegment(QuotationFlightSegment s) { s.setQuotation(this); this.flightSegments.add(s); }
    public void addHotel(QuotationHotel h)                 { h.setQuotation(this); this.hotels.add(h); }
    public void addSightseeingDay(QuotationSightseeingDay d){ d.setQuotation(this); this.sightseeingDays.add(d); }
    public void addCruise(QuotationCruise c)               { c.setQuotation(this); this.cruises.add(c); }
    public void addVehicle(QuotationVehicle v)             { v.setQuotation(this); this.vehicles.add(v); }
    public void addAddon(QuotationAddon a)                 { a.setQuotation(this); this.addons.add(a); }
}