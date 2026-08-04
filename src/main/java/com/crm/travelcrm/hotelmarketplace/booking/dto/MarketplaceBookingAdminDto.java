package com.crm.travelcrm.hotelmarketplace.booking.dto;

import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
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
 * A marketplace booking as the SUPERADMIN sees it: everything, including the two figures a tenant
 * must never receive ({@link #supplierTotal}, {@link #platformEarning}) and the operational state of
 * the CRM projection.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketplaceBookingAdminDto {

    UUID publicId;
    String bookingCode;
    MarketplaceBookingStatus status;

    Long tenantId;
    String tenantCode;
    String tenantName;

    UUID hotelPublicId;
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

    // ── Money: the full picture ─────────────────────────────────────────────
    BigDecimal quotedTenantPayable;
    BigDecimal supplierTotal;
    BigDecimal platformEarning;
    BigDecimal tenantPayable;
    BigDecimal tenantCustomerSellingAmount;
    String currency;

    String supplierConfirmationNumber;
    String cancellationTerms;
    String priceRevisionReason;
    String rejectionReason;
    String internalNotes;

    Long approvedBySuperAdminId;
    LocalDateTime approvedAt;

    // ── An open price revision (design §8 Step 6B) ──────────────────────────
    // The SuperAdmin view carries BOTH sides of the proposal, including the supplier figure the
    // tenant DTO has no field for.
    BigDecimal revisedSupplierTotal;
    BigDecimal revisedTenantPayable;
    BigDecimal revisionPreviousPayable;
    String revisedCancellationTerms;
    LocalDateTime revisionRequestedAt;
    LocalDateTime revisionExpiresAt;
    LocalDateTime revisionRespondedAt;
    Integer revisionCount;

    // ── Cancellation (design §9) ────────────────────────────────────────────
    LocalDateTime cancelRequestedAt;
    String cancelRequestReason;
    LocalDateTime cancelledAt;
    String cancellationReason;
    BigDecimal cancellationCharge;
    BigDecimal tenantRefundAmount;
    Long cancelledBySuperAdminId;

    // ── Voucher (design §7) ─────────────────────────────────────────────────
    VoucherStatus voucherStatus;
    String voucherNumber;
    LocalDateTime voucherIssuedAt;
    LocalDateTime voucherRevokedAt;

    // ── CRM projection health ───────────────────────────────────────────────
    UUID crmBookingPublicId;
    UUID crmServiceItemPublicId;
    UUID crmExpensePublicId;
    CrmSyncState crmSyncState;
    String crmSyncError;
    Integer crmSyncAttempts;

    LocalDateTime createdAt;
}
