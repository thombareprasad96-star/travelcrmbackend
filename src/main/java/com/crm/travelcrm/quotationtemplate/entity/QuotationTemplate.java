package com.crm.travelcrm.quotationtemplate.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A reusable package blueprint an agent can score against a lead and then clone into a real
 * {@code Quotation}. Tenant-scoped; soft-deleted through the explicit {@code deletedAt IS NULL}
 * finders (this module deliberately does not opt into {@code softDeleteFilter}, matching its
 * sibling {@code Quotation} rather than the master-data modules).
 *
 * <p>The five scalar fields below exist because the matcher scores exactly five dimensions.
 * {@link #durationNights}, {@link #hotelTier}, {@link #basePrice} and {@link #seasonMonths} are
 * denormalized onto the root on purpose: scoring reads them for every candidate template, and a
 * per-row aggregate over the children would turn one ranking pass into an N+1.
 *
 * <p>Children mirror the shape of the quotation they will become — free-text name snapshots, no
 * master FKs — with one exception: {@code QuotationTemplateItinerary.cityId} keeps the real
 * {@code City} id, because destination scoring must compare identities, not spellings. That id is
 * validated tenant-owned in the service; there is no DB-level FK, matching how {@code Booking.leadId}
 * and friends are handled.
 */
@Entity
@Table(
        name = "quotation_templates",
        indexes = {
                @Index(name = "idx_qtpl_tenant", columnList = "tenant_id"),
                @Index(name = "idx_qtpl_active", columnList = "active")
        }
        // No unique constraint on (tenant_id, name): with soft delete a trashed template would
        // permanently burn its name. The duplicate check lives in the service, where it can scope
        // itself to live rows.
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationTemplate extends BaseTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Cloudinary URL; carried onto the cloned quotation's cover image. */
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    /** Archived templates stay listable and stay attached to past quotations, but never match. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    /**
     * The package's structured cancellation policy — the publicId of a {@code CancellationPolicy}
     * version (level = PACKAGE, owner = this template's publicId). Carried onto a quotation cloned
     * from this template, and from there pinned onto the booking. Null → the tenant company default
     * governs cancellations of bookings from this package.
     */
    @Column(name = "cancellation_policy_public_id")
    private java.util.UUID cancellationPolicyPublicId;

    // ── Scoring dimensions ────────────────────────────────────────────────────

    /** Total nights the package runs for. Derived from the itinerary when the client omits it. */
    @Column(name = "duration_nights")
    private Integer durationNights;

    /** Star tier of the package, 1–5. Scored against the tier the agent picks in the match panel. */
    @Column(name = "hotel_tier")
    private Integer hotelTier;

    /**
     * Indicative all-in package price, in the same unit as {@code Lead.budget} (single-currency,
     * like the rest of the pricing in this codebase). Used to score budget fit and to render the
     * template card — it is <b>not</b> copied onto the cloned quotation, whose totals are always
     * recomputed from its own line items.
     */
    @Column(name = "base_price", precision = 15, scale = 2)
    private BigDecimal basePrice;

    /**
     * Months (1–12) this package is sold in. <b>Empty means year-round</b> and scores a perfect
     * season fit, which is why this is a Set rather than a nullable column.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "quotation_template_season_months",
            joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "month", nullable = false)
    @BatchSize(size = 50)
    @Builder.Default
    private Set<Integer> seasonMonths = new LinkedHashSet<>();

    // ── Children ──────────────────────────────────────────────────────────────
    // Two bags plus an element collection. They are never JOIN FETCHed together (that is a
    // MultipleBagFetchException); @BatchSize collapses the ranking pass into a couple of extra
    // selects instead, exactly as Quotation does.

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dayNumber ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationTemplateItinerary> itinerary = new ArrayList<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationTemplateHotel> hotels = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_template_inclusions", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> inclusions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_template_exclusions", joinColumns = @JoinColumn(name = "template_id"))
    @Column(name = "item", columnDefinition = "TEXT")
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> exclusions = new ArrayList<>();

    // ── Bi-directional wiring helpers ─────────────────────────────────────────
    public void addItineraryDay(QuotationTemplateItinerary day) {
        day.setTemplate(this);
        this.itinerary.add(day);
    }

    public void addHotel(QuotationTemplateHotel hotel) {
        hotel.setTemplate(this);
        this.hotels.add(hotel);
    }

    /** Nights + 1, the way an agent says it out loud ("6 days / 5 nights"). Never persisted. */
    @Transient
    public Integer getDurationDays() {
        return durationNights == null ? null : durationNights + 1;
    }
}
