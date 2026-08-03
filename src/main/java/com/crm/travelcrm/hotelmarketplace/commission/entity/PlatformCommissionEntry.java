package com.crm.travelcrm.hotelmarketplace.commission.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One immutable line of the platform's earning ledger (design doc §5.12).
 *
 * <p><b>Append-only.</b> The platform's historical earnings must never be recomputed from current
 * master, rule or booking data — a cancellation restates the booking, a commercial-rule edit restates
 * the rate, and either would silently rewrite last quarter's revenue. Everything that happened to the
 * money is a row; nothing is an edit. The single exception is {@link #status}, which is a claim about
 * how real an amount is, not about what the amount was.</p>
 *
 * <p><b>Platform-owned.</b> Extends {@link BaseEntity} with an explicit {@code tenantId}, the
 * {@code UpgradeRequest}/{@code PlatformHotelBooking} shape: the SuperAdmin report reads across every
 * tenant, so the Hibernate {@code tenantFilter} must not apply. The consequence is the usual one —
 * nothing scopes a read here for you. There is deliberately <b>no tenant-facing view of this table at
 * all</b>, because every row carries {@code supplierTotal} and the platform's margin.</p>
 *
 * <p><b>Self-contained.</b> {@code bookingCode}, {@code tenantCode}, {@code supplierTotal} and
 * {@code tenantPayable} are snapshotted rather than joined, so a revenue report can be reconstructed
 * from this table alone even after the booking it came from has been cancelled and restated.</p>
 */
@Entity
@Table(
        name = "platform_hotel_commission_ledger",
        indexes = {
                @Index(name = "idx_phcl_booking", columnList = "hotel_booking_id"),
                @Index(name = "idx_phcl_tenant", columnList = "tenant_id"),
                @Index(name = "idx_phcl_status", columnList = "status"),
                @Index(name = "idx_phcl_effective", columnList = "effective_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformCommissionEntry extends BaseEntity {

    /** FK to {@code platform_hotel_bookings.id}. Kept as a plain Long — platform rows hold no associations. */
    @Column(name = "hotel_booking_id", nullable = false)
    private Long hotelBookingId;

    /** The booking's external identity, so a report never has to expose or resolve the internal id. */
    @Column(name = "hotel_booking_public_id", nullable = false)
    private UUID hotelBookingPublicId;

    @Column(name = "booking_code", length = 40)
    private String bookingCode;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private CommissionEntryType entryType;

    /**
     * Signed. ACCRUAL positive, REVERSAL negative, ADJUSTMENT either way.
     *
     * <p>Never an unsigned magnitude plus a type — that pushes the sign decision out to every caller
     * and every reporting query, and one of them will get it backwards.</p>
     */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CommissionEntryStatus status = CommissionEntryStatus.PENDING;

    /** The economics this row was derived from, as they stood when it was written. */
    @Column(name = "supplier_total", precision = 15, scale = 2)
    private BigDecimal supplierTotal;

    @Column(name = "tenant_payable", precision = 15, scale = 2)
    private BigDecimal tenantPayable;

    /**
     * The accounting date, which is not the row's {@code createdAt}: an accrual belongs to the day the
     * booking was approved even when a retry writes it two days later.
     */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /**
     * Idempotency. Unique across live rows ({@code uq_phcl_reference}).
     *
     * <p>The approval path is replayed by {@code MarketplaceCrmSyncScheduler}, and a replay that
     * accrued a second time would overstate platform revenue permanently — no later reconciliation
     * could tell the duplicate from a legitimate second entry, because both are valid-looking rows for
     * the same booking.</p>
     */
    @Column(name = "reference_key", nullable = false, length = 120)
    private String referenceKey;
}
