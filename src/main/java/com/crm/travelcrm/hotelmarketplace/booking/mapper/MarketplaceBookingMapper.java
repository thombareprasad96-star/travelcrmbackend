package com.crm.travelcrm.hotelmarketplace.booking.mapper;

import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingAdminDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import org.springframework.stereotype.Component;

/**
 * Entity → DTO for the two audiences.
 *
 * <p>Hand-written, not generated, for the same reason the catalog mapper is: a generated mapper's
 * job is to copy every field it can match, and the two fields that must NEVER reach a tenant
 * ({@code supplierTotal}, {@code platformEarning}) are ordinary {@code BigDecimal}s that any such
 * tool would happily map the moment someone adds them to the tenant DTO.</p>
 */
@Component
public class MarketplaceBookingMapper {

    /** Tenant view. Physically cannot carry supplier cost or platform earning — they are not fields here. */
    public MarketplaceBookingTenantDto toTenantDto(PlatformHotelBooking b, String crmBookingCode) {
        return MarketplaceBookingTenantDto.builder()
                .publicId(b.getPublicId())
                .bookingCode(b.getBookingCode())
                .status(b.getStatus())
                .hotelName(b.getHotelNameSnapshot())
                .cityName(b.getCityNameSnapshot())
                .countryCode(b.getCountryCodeSnapshot())
                .address(b.getAddressSnapshot())
                .roomName(b.getRoomNameSnapshot())
                .mealPlan(b.getMealPlanSnapshot())
                .checkIn(b.getCheckIn())
                .checkOut(b.getCheckOut())
                .nights(b.getNights())
                .rooms(b.getRooms())
                .adults(b.getAdults())
                .children(b.getChildren())
                .leadGuestName(b.getLeadGuestName())
                .leadGuestPhone(b.getLeadGuestPhone())
                .leadGuestEmail(b.getLeadGuestEmail())
                .specialRequests(b.getSpecialRequests())
                .tenantPayable(b.getTenantPayable())
                .quotedTenantPayable(b.getQuotedTenantPayable())
                .tenantCustomerSellingAmount(b.getTenantCustomerSellingAmount())
                .currency(b.getCurrency())
                .supplierConfirmationNumber(b.getSupplierConfirmationNumber())
                .cancellationTerms(b.getCancellationTermsSnapshot())
                .priceRevisionReason(b.getPriceRevisionReason())
                .rejectionReason(b.getRejectionReason())
                // Revision: the proposal, minus the supplier figure — the tenant DTO has no field
                // for it, which is the point of a separate class rather than @JsonIgnore.
                .revisedTenantPayable(b.getRevisedTenantPayable())
                .revisionPreviousPayable(b.getRevisionPreviousPayable())
                .revisedCancellationTerms(b.getRevisedCancellationTerms())
                .revisionRequestedAt(b.getRevisionRequestedAt())
                .revisionExpiresAt(b.getRevisionExpiresAt())
                .revisionCount(b.getRevisionCount())
                .cancelRequestedAt(b.getCancelRequestedAt())
                .cancelRequestReason(b.getCancelRequestReason())
                .cancelledAt(b.getCancelledAt())
                .cancellationReason(b.getCancellationReason())
                .cancellationCharge(b.getCancellationCharge())
                .tenantRefundAmount(b.getTenantRefundAmount())
                .voucherStatus(b.getVoucherStatus())
                .voucherNumber(b.getVoucherNumber())
                .voucherIssuedAt(b.getVoucherIssuedAt())
                .crmBookingPublicId(b.getCrmBookingPublicId())
                .crmBookingCode(crmBookingCode)
                .approvedAt(b.getApprovedAt())
                .createdAt(b.getCreatedAt())
                .build();
    }

    public MarketplaceBookingAdminDto toAdminDto(PlatformHotelBooking b) {
        return MarketplaceBookingAdminDto.builder()
                .publicId(b.getPublicId())
                .bookingCode(b.getBookingCode())
                .status(b.getStatus())
                .tenantId(b.getTenantId())
                .tenantCode(b.getTenantCode())
                .tenantName(b.getTenantName())
                .hotelPublicId(b.getPlatformHotelPublicId())
                .hotelName(b.getHotelNameSnapshot())
                .cityName(b.getCityNameSnapshot())
                .countryCode(b.getCountryCodeSnapshot())
                .address(b.getAddressSnapshot())
                .roomName(b.getRoomNameSnapshot())
                .mealPlan(b.getMealPlanSnapshot())
                .checkIn(b.getCheckIn())
                .checkOut(b.getCheckOut())
                .nights(b.getNights())
                .rooms(b.getRooms())
                .adults(b.getAdults())
                .children(b.getChildren())
                .leadGuestName(b.getLeadGuestName())
                .leadGuestPhone(b.getLeadGuestPhone())
                .leadGuestEmail(b.getLeadGuestEmail())
                .specialRequests(b.getSpecialRequests())
                .quotedTenantPayable(b.getQuotedTenantPayable())
                .supplierTotal(b.getSupplierTotal())
                .platformEarning(b.getPlatformEarning())
                .tenantPayable(b.getTenantPayable())
                .tenantCustomerSellingAmount(b.getTenantCustomerSellingAmount())
                .currency(b.getCurrency())
                .supplierConfirmationNumber(b.getSupplierConfirmationNumber())
                .cancellationTerms(b.getCancellationTermsSnapshot())
                .priceRevisionReason(b.getPriceRevisionReason())
                .rejectionReason(b.getRejectionReason())
                .internalNotes(b.getInternalNotes())
                .approvedBySuperAdminId(b.getApprovedBySuperAdminId())
                .approvedAt(b.getApprovedAt())
                .revisedSupplierTotal(b.getRevisedSupplierTotal())
                .revisedTenantPayable(b.getRevisedTenantPayable())
                .revisionPreviousPayable(b.getRevisionPreviousPayable())
                .revisedCancellationTerms(b.getRevisedCancellationTerms())
                .revisionRequestedAt(b.getRevisionRequestedAt())
                .revisionExpiresAt(b.getRevisionExpiresAt())
                .revisionRespondedAt(b.getRevisionRespondedAt())
                .revisionCount(b.getRevisionCount())
                .cancelRequestedAt(b.getCancelRequestedAt())
                .cancelRequestReason(b.getCancelRequestReason())
                .cancelledAt(b.getCancelledAt())
                .cancellationReason(b.getCancellationReason())
                .cancellationCharge(b.getCancellationCharge())
                .tenantRefundAmount(b.getTenantRefundAmount())
                .cancelledBySuperAdminId(b.getCancelledBySuperAdminId())
                .voucherStatus(b.getVoucherStatus())
                .voucherNumber(b.getVoucherNumber())
                .voucherIssuedAt(b.getVoucherIssuedAt())
                .voucherRevokedAt(b.getVoucherRevokedAt())
                .crmBookingPublicId(b.getCrmBookingPublicId())
                .crmServiceItemPublicId(b.getCrmServiceItemPublicId())
                .crmExpensePublicId(b.getCrmExpensePublicId())
                .crmSyncState(b.getCrmSyncState())
                .crmSyncError(b.getCrmSyncError())
                .crmSyncAttempts(b.getCrmSyncAttempts())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
