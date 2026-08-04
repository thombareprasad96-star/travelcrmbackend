package com.crm.travelcrm.hotelmarketplace.booking.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.enums.VoucherSource;
import com.crm.travelcrm.hotelmarketplace.booking.enums.VoucherStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tenant's request to book a catalog hotel, and the SuperAdmin's decision on it.
 *
 * <p><b>Extends {@link BaseEntity} but carries a required {@code tenantId}</b> — the same shape as
 * {@code UpgradeRequest} and {@code SubAgentLicenseRequest}. It is platform-owned (the SuperAdmin
 * queue reads across every tenant, so the Hibernate {@code tenantFilter} must not apply) while still
 * naming exactly one tenant. The consequence is the important part: <b>nothing scopes a read here
 * automatically, and {@code TenantIsolationArchTest} does not cover this repository either</b>,
 * because that guard is scoped to {@code BaseTenantEntity} repositories. Every tenant-facing read
 * must go through an explicit ownership check.</p>
 *
 * <p><b>Snapshots, not references.</b> The catalog can be edited or unpublished after a request is
 * raised; what was asked for and what was agreed must not move underneath it.</p>
 *
 * <p><b>Two money vocabularies, deliberately separated.</b> {@code supplierTotal} and
 * {@code platformEarning} are the platform's economics and must never reach a tenant DTO;
 * {@code tenantPayable} is what the tenant owes, and {@code tenantCustomerSellingAmount} is the
 * tenant's own price to their customer, which the platform records but never sets.</p>
 */
@Entity
@Table(
        name = "platform_hotel_bookings",
        indexes = {
                @Index(name = "idx_phb_tenant", columnList = "tenant_id"),
                @Index(name = "idx_phb_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformHotelBooking extends BaseEntity {

    /** Logical FK — no DB constraint, because the SuperAdmin queue is cross-tenant by design. */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Column(name = "tenant_name", length = 200)
    private String tenantName;

    @Column(name = "booking_code", nullable = false, length = 40)
    private String bookingCode;

    /**
     * Tenant-supplied, unique per tenant. The ONLY thing standing between a double-submit and two
     * CRM bookings — {@code BookingServiceImpl.create()} has no idempotency of its own.
     */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    // ── What was asked for ──────────────────────────────────────────────────

    @Column(name = "platform_hotel_id", nullable = false)
    private Long platformHotelId;

    @Column(name = "platform_hotel_public_id", nullable = false)
    private UUID platformHotelPublicId;

    @Column(name = "platform_room_public_id")
    private UUID platformRoomPublicId;

    @Column(name = "platform_meal_plan_public_id")
    private UUID platformMealPlanPublicId;

    @Column(name = "hotel_name_snapshot", nullable = false, length = 200)
    private String hotelNameSnapshot;

    @Column(name = "city_name_snapshot", length = 120)
    private String cityNameSnapshot;

    @Column(name = "country_code_snapshot", length = 3)
    private String countryCodeSnapshot;

    @Column(name = "address_snapshot", length = 500)
    private String addressSnapshot;

    @Column(name = "room_name_snapshot", length = 150)
    private String roomNameSnapshot;

    @Column(name = "meal_plan_snapshot", length = 150)
    private String mealPlanSnapshot;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private Integer nights;

    @Column(nullable = false)
    @Builder.Default
    private Integer rooms = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer adults = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer children = 0;

    @Column(name = "lead_guest_name", nullable = false, length = 150)
    private String leadGuestName;

    @Column(name = "lead_guest_phone", length = 30)
    private String leadGuestPhone;

    @Column(name = "lead_guest_email", length = 150)
    private String leadGuestEmail;

    @Column(name = "special_requests", columnDefinition = "TEXT")
    private String specialRequests;

    // ── State ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private MarketplaceBookingStatus status = MarketplaceBookingStatus.REQUESTED;

    /**
     * Who submitted it. Captured at request time on the tenant's own thread, because the approval
     * thread has no tenant user to derive it from.
     */
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    // ── Money (numeric, never double) ───────────────────────────────────────

    /** What the tenant was shown when they submitted. Kept so a revision can be explained. */
    @Column(name = "quoted_tenant_payable", precision = 15, scale = 2)
    private BigDecimal quotedTenantPayable;

    /** SuperAdmin-only. Never mapped onto a tenant DTO. */
    @Column(name = "supplier_total", precision = 15, scale = 2)
    private BigDecimal supplierTotal;

    /** SuperAdmin-only. */
    @Column(name = "platform_earning", precision = 15, scale = 2)
    private BigDecimal platformEarning;

    /** What the tenant owes the platform. The only figure of the three a tenant may see. */
    @Column(name = "tenant_payable", precision = 15, scale = 2)
    private BigDecimal tenantPayable;

    /** The tenant's price to their own customer. Recorded, never set by the platform. */
    @Column(name = "tenant_customer_selling_amount", precision = 15, scale = 2)
    private BigDecimal tenantCustomerSellingAmount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    // ── Decision ────────────────────────────────────────────────────────────

    @Column(name = "supplier_confirmation_number", length = 100)
    private String supplierConfirmationNumber;

    @Column(name = "cancellation_terms_snapshot", columnDefinition = "TEXT")
    private String cancellationTermsSnapshot;

    @Column(name = "price_revision_reason", columnDefinition = "TEXT")
    private String priceRevisionReason;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "internal_notes", columnDefinition = "TEXT")
    private String internalNotes;

    @Column(name = "approved_by_super_admin_id")
    private Long approvedBySuperAdminId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // ── Price revision (design §8 Step 6B) ──────────────────────────────────
    // The proposed amounts live BESIDE the agreed ones, never on top of them, until the tenant
    // accepts. Writing a proposal straight into tenantPayable would make "what you owe" mean
    // "what you might owe" for as long as the tenant takes to answer — and a tenant who never
    // answers would be silently repriced.

    /** Proposed replacement for {@link #supplierTotal}. SuperAdmin-only. */
    @Column(name = "revised_supplier_total", precision = 15, scale = 2)
    private BigDecimal revisedSupplierTotal;

    /** Proposed replacement for {@link #tenantPayable}. The number the tenant is being asked about. */
    @Column(name = "revised_tenant_payable", precision = 15, scale = 2)
    private BigDecimal revisedTenantPayable;

    /**
     * What the payable was before this revision. Kept explicitly because acceptance overwrites
     * {@link #tenantPayable}, and "old amount, new amount, difference" (§8 Step 6B) is unreconstructable
     * afterwards otherwise.
     */
    @Column(name = "revision_previous_payable", precision = 15, scale = 2)
    private BigDecimal revisionPreviousPayable;

    /** Terms that would replace {@link #cancellationTermsSnapshot} if accepted. */
    @Column(name = "revised_cancellation_terms", columnDefinition = "TEXT")
    private String revisedCancellationTerms;

    @Column(name = "revision_requested_at")
    private LocalDateTime revisionRequestedAt;

    /** After this, the offer is stale and acceptance is refused. */
    @Column(name = "revision_expires_at")
    private LocalDateTime revisionExpiresAt;

    @Column(name = "revision_responded_at")
    private LocalDateTime revisionRespondedAt;

    /** How many times the price has been renegotiated. Visible in the queue as a friction signal. */
    @Column(name = "revision_count", nullable = false)
    @Builder.Default
    private Integer revisionCount = 0;

    // ── Cancellation (design §9) ────────────────────────────────────────────

    @Column(name = "cancel_requested_at")
    private LocalDateTime cancelRequestedAt;

    @Column(name = "cancel_request_reason", columnDefinition = "TEXT")
    private String cancelRequestReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /** What the supplier retained. The tenant still owes this after cancelling. */
    @Column(name = "cancellation_charge", precision = 15, scale = 2)
    private BigDecimal cancellationCharge;

    /** What comes back to the tenant. {@code tenantPayable - cancellationCharge}, server-computed. */
    @Column(name = "tenant_refund_amount", precision = 15, scale = 2)
    private BigDecimal tenantRefundAmount;

    @Column(name = "cancelled_by_super_admin_id")
    private Long cancelledBySuperAdminId;

    // ── The cancellation quote the tenant has to accept (design §9 clauses 1-3) ──
    // Beside the settled figures, never on top of them — the same rule the revision fields follow.
    // A proposed charge written into cancellationCharge would read as "this is what you were
    // charged" to every screen, for a cancellation that has not happened and may never.

    @Column(name = "quoted_cancellation_charge", precision = 15, scale = 2)
    private BigDecimal quotedCancellationCharge;

    /** SuperAdmin-only. What the platform proposes to keep of its earning. */
    @Column(name = "quoted_retained_earning", precision = 15, scale = 2)
    private BigDecimal quotedRetainedEarning;

    /**
     * Why the charge is what it is, in the tenant's words rather than the operator's.
     *
     * <p>Tenant-visible, unlike {@code internalNotes}. A charge without a reason is one the tenant
     * cannot explain to their own customer, which is the same problem {@code priceRevisionReason}
     * exists to solve on the revision side.</p>
     */
    @Column(name = "cancellation_quote_note", columnDefinition = "TEXT")
    private String cancellationQuoteNote;

    @Column(name = "cancellation_quoted_at")
    private LocalDateTime cancellationQuotedAt;

    @Column(name = "cancellation_quote_expires_at")
    private LocalDateTime cancellationQuoteExpiresAt;

    // ── Voucher (design §7) ─────────────────────────────────────────────────
    // Tracked SEPARATELY from status on purpose: a PDF that fails to render must not be able to
    // un-confirm a room the supplier has already held.

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_status", nullable = false, length = 20)
    @Builder.Default
    private VoucherStatus voucherStatus = VoucherStatus.NOT_ISSUED;

    /**
     * Whether the voucher is the one this system renders, or a PDF the hotel supplied.
     *
     * <p>Only the discriminator lives here. The bytes are in {@code platform_hotel_voucher_files},
     * a side table, because this entity is loaded on every queue and list page and a {@code bytea}
     * column on it would drag a multi-megabyte PDF through each one.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_source", nullable = false, length = 20)
    @Builder.Default
    private VoucherSource voucherSource = VoucherSource.GENERATED;

    @Column(name = "voucher_number", length = 40)
    private String voucherNumber;

    @Column(name = "voucher_issued_at")
    private LocalDateTime voucherIssuedAt;

    @Column(name = "voucher_revoked_at")
    private LocalDateTime voucherRevokedAt;

    // ── The CRM link ────────────────────────────────────────────────────────

    @Column(name = "crm_booking_public_id")
    private UUID crmBookingPublicId;

    @Column(name = "crm_service_item_public_id")
    private UUID crmServiceItemPublicId;

    @Column(name = "crm_expense_public_id")
    private UUID crmExpensePublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "crm_sync_state", nullable = false, length = 20)
    @Builder.Default
    private CrmSyncState crmSyncState = CrmSyncState.PENDING;

    @Column(name = "crm_sync_error", columnDefinition = "TEXT")
    private String crmSyncError;

    @Column(name = "crm_sync_attempts", nullable = false)
    @Builder.Default
    private Integer crmSyncAttempts = 0;

    /** Optimistic lock — two SuperAdmins deciding the same request must not both win. */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
