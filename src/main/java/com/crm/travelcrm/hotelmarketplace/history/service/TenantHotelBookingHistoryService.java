package com.crm.travelcrm.hotelmarketplace.history.service;

import com.crm.travelcrm.booking.api.CrmBookingLinkPort;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.voucher.service.MarketplaceVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only access to a tenant's own marketplace hotel bookings, and to their issued vouchers.
 *
 * <p><b>Entitlement-neutral by construction</b> (design §20.5). This backs {@code /api/me/hotel-bookings},
 * which {@code ModuleAccessFilter} does not gate — a tenant whose {@code HOTEL_MARKETPLACE} add-on
 * lapses must not lose the voucher for a stay next week. Everything that could still <i>sell</i>
 * (search, import, submit, accept a revision) stays under {@code /api/hotel-marketplace} and stays
 * gated. Nothing on this service mutates anything, which is what makes that split safe.</p>
 *
 * <p><b>Ownership lives in the query.</b> {@code platform_hotel_bookings} extends {@code BaseEntity},
 * so no Hibernate tenant filter touches it and {@code TenantIsolationArchTest} does not cover this
 * repository — a bare by-publicId read here would serve another tenant's guest details, phone numbers
 * and negotiated payable with nothing in the build to catch it. Every read below goes through
 * {@code findByPublicIdAndTenantIdAndDeletedAtIsNull}, so a foreign id is a 404, never data.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantHotelBookingHistoryService {

    private final PlatformHotelBookingRepository repository;
    private final MarketplaceBookingMapper mapper;
    private final CrmBookingLinkPort crmLink;
    private final MarketplaceVoucherService voucherService;

    @Transactional(readOnly = true)
    public Page<MarketplaceBookingTenantDto> list(Pageable pageable) {
        return repository.findByTenantIdAndDeletedAtIsNullOrderByIdDesc(requireTenantId(), pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public MarketplaceBookingTenantDto get(UUID publicId) {
        return toDto(require(publicId));
    }

    /**
     * The issued voucher, rendered on the fly.
     *
     * <p>An un-issued or withdrawn voucher is a <b>404, not a 403</b>: the tenant is entitled to this
     * booking's voucher, the document simply does not exist yet. A 403 would tell them to go and buy
     * something, which is the wrong instruction and — for a lapsed add-on — the exact confusion §20.5
     * exists to prevent.</p>
     */
    @Transactional(readOnly = true)
    public MarketplaceVoucherService.VoucherDocument voucherDocument(UUID publicId) {
        PlatformHotelBooking row = require(publicId);
        if (row.getVoucherStatus() == null || !row.getVoucherStatus().isDownloadable()) {
            throw new ResourceNotFoundException(
                    "No voucher has been issued for booking " + row.getBookingCode() + ".");
        }
        log.info("Tenant voucher download for marketplace booking {}", row.getBookingCode());
        // The hotel's own document when there is one — that is what the desk recognises.
        return voucherService.download(row);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private PlatformHotelBooking require(UUID publicId) {
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Hotel booking not found: " + publicId));
    }

    private MarketplaceBookingTenantDto toDto(PlatformHotelBooking b) {
        // Through the port, never the CRM repository — history is exactly where the marketplace is
        // most tempted to reach into booking's persistence layer for one string, and the module has to
        // stay removable.
        String crmCode = b.getCrmBookingPublicId() == null ? null
                : crmLink.lookup(b.getCrmBookingPublicId(), b.getTenantId())
                        .map(CrmBookingLinkPort.CrmBookingRef::bookingCode)
                        .orElse(null);
        return mapper.toTenantDto(b, crmCode);
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty — the hotel booking history API is tenant-facing.");
        }
        return tenantId;
    }
}
