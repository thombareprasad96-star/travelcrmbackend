package com.crm.travelcrm.booking.cancellation.entity;

import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A tenant's cancellation-charge policy — a set of {@link CancellationPolicyBand} slabs keyed by
 * days-before-departure, plus the tax-treatment switches applied to the retained charge.
 *
 * <p><b>Immutable, versioned.</b> A policy is NEVER edited in place. Publishing a change writes a
 * brand-new row (its own {@code id}/{@code publicId}, {@code version = previous + 1}, its own band
 * rows) and deactivates the prior active row. This is the backbone of the anti-retroactivity
 * guarantee: a booking pins the exact version row it was created under
 * ({@code Booking.cancellationPolicyPublicId} + {@code cancellationPolicyVersion}), so editing the
 * policy tomorrow produces a new version and can never change how a booking made today is charged.
 * Never use {@code orphanRemoval} to swap bands on an existing row, and never hard-delete a version
 * that a live booking pins (guarded in the service — there is no DB FK, matching the codebase style).
 *
 * <p><b>Resolution.</b> {@code level = COMPANY} rows (owner {@code null}) are the tenant default —
 * one active version at a time, chosen point-in-time via {@link #effectiveFrom}. {@code level =
 * PACKAGE} rows carry the owning package's publicId in {@link #ownerPublicId} and override the
 * company default for bookings converted from that package.
 */
@Entity
@Table(
        name = "cancellation_policies",
        indexes = {
                @Index(name = "idx_cancelpol_tenant", columnList = "tenant_id"),
                @Index(name = "idx_cancelpol_lookup", columnList = "tenant_id,level,owner_public_id,active"),
                @Index(name = "idx_cancelpol_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CancellationPolicy extends BaseTenantEntity {

    /** Human label, e.g. "Company default" or "Bali Premium 6N". */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 10)
    private CancellationPolicyLevel level;

    /**
     * The owning package's publicId when {@link #level} is {@code PACKAGE}; {@code null} for the
     * {@code COMPANY} default. Logical reference (no DB FK), matching the codebase convention.
     */
    @Column(name = "owner_public_id")
    private UUID ownerPublicId;

    /** Monotonic per (tenant, level, owner). Display/provenance; the row's publicId is the true pin key. */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /** Exactly one active version per (tenant, level, owner). Superseded versions are retained (active=false). */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    /**
     * The date from which this version governs. Used for point-in-time resolution of legacy
     * (unpinned) bookings: "the active company version with the greatest effectiveFrom &lt;=
     * bookingDate". Set explicitly on publish — never derived from {@code createdAt}, whose
     * semantics were not designed for as-of lookup and are unorderable for same-day re-edits.
     */
    @Builder.Default
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom = LocalDate.now();

    /**
     * Whether GST is due on the retained cancellation charge (a "tolerating an act" supply).
     * When true the calculator retains the GST proportional to the retained base; when false the
     * customer's GST is fully refunded. Derived from the booking's STORED gst, never a live rate.
     */
    @Builder.Default
    @Column(name = "gst_on_charge_applicable", nullable = false)
    private Boolean gstOnChargeApplicable = Boolean.TRUE;

    /**
     * Whether TCS on the un-availed portion is refundable to the customer (default true → retained
     * TCS is zero). When false, TCS is retained proportional to the retained base.
     */
    @Builder.Default
    @Column(name = "tcs_refundable", nullable = false)
    private Boolean tcsRefundable = Boolean.TRUE;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "cancellation_policy_bands",
            joinColumns = @JoinColumn(name = "policy_id"))
    @OrderBy("minDaysBeforeDeparture DESC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<CancellationPolicyBand> bands = new ArrayList<>();
}