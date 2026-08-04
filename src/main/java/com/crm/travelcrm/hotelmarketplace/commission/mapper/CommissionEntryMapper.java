package com.crm.travelcrm.hotelmarketplace.commission.mapper;

import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionEntryDto;
import com.crm.travelcrm.hotelmarketplace.commission.entity.PlatformCommissionEntry;
import org.springframework.stereotype.Component;

/**
 * Entity → DTO for the one audience this table has.
 *
 * <p>Hand-written like every other mapper in the marketplace. Here the reason is not the usual
 * two-audience one — there is no tenant DTO to leak into — but the same rule holds: a generated mapper
 * would silently start copying {@code supplierTotal} into whatever tenant-shaped class someone adds
 * next, and this is the table where that field lives on every single row.</p>
 */
@Component
public class CommissionEntryMapper {

    public CommissionEntryDto toDto(PlatformCommissionEntry e) {
        return CommissionEntryDto.builder()
                .publicId(e.getPublicId())
                .hotelBookingPublicId(e.getHotelBookingPublicId())
                .bookingCode(e.getBookingCode())
                .tenantId(e.getTenantId())
                .tenantCode(e.getTenantCode())
                .entryType(e.getEntryType())
                .status(e.getStatus())
                .amount(e.getAmount())
                .currency(e.getCurrency())
                .supplierTotal(e.getSupplierTotal())
                .tenantPayable(e.getTenantPayable())
                .effectiveDate(e.getEffectiveDate())
                .reason(e.getReason())
                .referenceKey(e.getReferenceKey())
                .createdAt(e.getCreatedAt())
                .createdBy(e.getCreatedBy())
                .build();
    }
}
