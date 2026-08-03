package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A detailed service line on a booking (hotel stay, flight, transfer, sightseeing, visa …).
 * This is the operational breakdown used by the Booking → Services screen: each line can be
 * assigned a vendor, carry a confirmation number, and drive a per-service voucher. It is
 * SEPARATE from {@link Booking#getServices()} (the lightweight {@code List<String>} tags shown
 * on the list view) and does NOT auto-mutate the booking's financial totals — those remain the
 * figures entered on the booking itself.
 *
 * <p>Tenant-scoped; soft-delete via {@code deletedAt}. The vendor link is a snapshot
 * ({@code vendorId}/{@code vendorPublicId}/{@code vendorNameSnapshot}) so the line stays
 * meaningful even if the vendor master later changes.</p>
 */
@Entity
@Table(
        name = "booking_service_items",
        indexes = {
                @Index(name = "idx_bksvc_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_bksvc_booking", columnList = "tenant_id,booking_id"),
                @Index(name = "idx_bksvc_vendor",  columnList = "vendor_id"),
                @Index(name = "idx_bksvc_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingServiceItem extends BaseTenantEntity {

    /** Owning booking — logical FK to {@code bookings.id} (no DB constraint, app-enforced). */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** e.g. "Hotel", "Flight", "Transport", "Sightseeing", "Visa", "Insurance" — free text. */
    @Column(name = "service_type", length = 60)
    private String serviceType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "service_date")
    private LocalDate serviceDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ServiceItemStatus status = ServiceItemStatus.PENDING;

    /** Customer-facing price for this line (informational; does not change booking totals). */
    @Column(name = "cost", precision = 12, scale = 2)
    private BigDecimal cost;

    /** What the agency pays its vendor for this line (sensitive — never on customer documents). */
    @Column(name = "vendor_cost", precision = 12, scale = 2)
    private BigDecimal vendorCost;

    // ── Vendor assignment (snapshot) ─────────────────────────────────────────
    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_public_id")
    private UUID vendorPublicId;

    @Column(name = "vendor_name_snapshot", length = 200)
    private String vendorNameSnapshot;

    /** Vendor/airline/hotel confirmation or PNR — printed on the service voucher. */
    @Column(name = "confirmation_number", length = 100)
    private String confirmationNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * The platform hotel-marketplace booking this line projects, when it came from there.
     *
     * <p>The link lives on this child table rather than on {@code Booking} on purpose: {@code Booking}
     * is {@code @Audited}, so a column added there needs its twin in {@code bookings_aud} or every
     * write to every booking fails. This entity is not audited.</p>
     *
     * <p>Unique per marketplace booking via a partial index, which is what makes the approval-time
     * projection a true UPSERT rather than an append.</p>
     */
    @Column(name = "marketplace_booking_public_id")
    private UUID marketplaceBookingPublicId;
}