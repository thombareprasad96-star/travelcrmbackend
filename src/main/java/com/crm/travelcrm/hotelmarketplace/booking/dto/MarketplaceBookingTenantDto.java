package com.crm.travelcrm.hotelmarketplace.booking.dto;

import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.enums.VoucherStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A marketplace booking as the TENANT sees it.
 *
 * <p>Deliberately absent: {@code supplierTotal} and {@code platformEarning}. From the tenant's point
 * of view the platform is simply the counterparty they owe — what the platform paid the hotel, and
 * what it kept, is not part of that relationship. Also absent: {@code internalNotes} and
 * {@code crmSyncState}, which are operational.</p>
 *
 * <p>A separate class from {@link MarketplaceBookingAdminDto} rather than one class with annotations:
 * hiding money behind {@code @JsonIgnore} is one careless edit away from a leak the compiler cannot
 * catch, and this is the exact split design doc §11 asks for.</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketplaceBookingTenantDto {

    UUID publicId;
    String bookingCode;
    MarketplaceBookingStatus status;

    String hotelName;
    String cityName;
    String countryCode;
    String address;
    String roomName;
    String mealPlan;

    LocalDate checkIn;
    LocalDate checkOut;
    Integer nights;
    Integer rooms;
    Integer adults;
    Integer children;

    String leadGuestName;
    String leadGuestPhone;
    String leadGuestEmail;
    String specialRequests;

    /** What the tenant owes the platform — the only money figure of the platform's three. */
    BigDecimal tenantPayable;

    /** What they were quoted at submit; differs from the above when a revision was accepted. */
    BigDecimal quotedTenantPayable;

    /** The tenant's own selling price, echoed back. */
    BigDecimal tenantCustomerSellingAmount;

    String currency;

    String supplierConfirmationNumber;
    String cancellationTerms;
    String priceRevisionReason;
    String rejectionReason;

    // ── An open price revision (design §8 Step 6B) ──────────────────────────
    // Present only while status is TENANT_APPROVAL_REQUIRED. revisedSupplierTotal is absent by
    // construction — what the platform pays the hotel is not part of this conversation.

    /** The amount being proposed. What the tenant is deciding about. */
    BigDecimal revisedTenantPayable;

    /** What it was before — so the UI can render the difference rather than make the tenant compute it. */
    BigDecimal revisionPreviousPayable;

    String revisedCancellationTerms;
    LocalDateTime revisionRequestedAt;
    LocalDateTime revisionExpiresAt;
    Integer revisionCount;

    // ── Cancellation (design §9) ────────────────────────────────────────────

    LocalDateTime cancelRequestedAt;
    String cancelRequestReason;
    LocalDateTime cancelledAt;
    String cancellationReason;

    // ── An open cancellation quote (design §9) ─────────────────────────────
    // quotedRetainedEarning is absent by construction: how the platform splits the charge with
    // itself is not part of what the tenant is being asked to accept.
    BigDecimal quotedCancellationCharge;
    String cancellationQuoteNote;
    LocalDateTime cancellationQuotedAt;
    LocalDateTime cancellationQuoteExpiresAt;

    /** What the supplier retained; still owed. */
    BigDecimal cancellationCharge;

    /** What comes back. Server-computed, never posted by the client. */
    BigDecimal tenantRefundAmount;

    // ── Voucher (design §7) ─────────────────────────────────────────────────

    VoucherStatus voucherStatus;
    String voucherNumber;
    LocalDateTime voucherIssuedAt;

    UUID crmBookingPublicId;
    String crmBookingCode;

    LocalDateTime approvedAt;
    LocalDateTime createdAt;
}
