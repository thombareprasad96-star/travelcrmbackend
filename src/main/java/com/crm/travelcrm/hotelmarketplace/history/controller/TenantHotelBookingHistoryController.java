package com.crm.travelcrm.hotelmarketplace.history.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.history.service.TenantHotelBookingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.crm.travelcrm.hotelmarketplace.voucher.service.MarketplaceVoucherService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A tenant's own hotel bookings and vouchers — <b>deliberately outside {@code /api/hotel-marketplace}</b>.
 *
 * <p>Design §20.5 and the acceptance criteria in §17 both require that suspending the
 * {@code HOTEL_MARKETPLACE} add-on "blocks new marketplace business without hiding confirmed
 * historical bookings or issued vouchers". Every other tenant marketplace route sits under
 * {@code /api/hotel-marketplace}, which {@code ModuleAccessFilter} hard-gates on that module — so a
 * lapsed renewal would take a guest's voucher for next week's stay with it. Mounting the read-only
 * half under {@code /api/me}, which is in the filter's {@code ALWAYS_ALLOWED} set, is what keeps the
 * operational duty alive after the commercial one ends.</p>
 *
 * <p><b>The split is the security boundary, so keep it exact.</b> Nothing here mutates: no submit, no
 * revision acceptance, no cancellation request. Anything that could sell or commit belongs on the
 * gated prefix. Adding a write to this controller silently sells to a suspended tenant, and adding a
 * {@code /api/me/hotel-bookings} rule to {@code ModuleAccessFilter.RULES} silently re-breaks the
 * acceptance criterion — do neither.</p>
 *
 * <p>Permission is still the second gate: the module answers "did this tenant buy it", the permission
 * answers "may this user". {@code HOTEL_MARKETPLACE_VIEW} survives a lapsed subscription because it
 * is granted per user, not per plan.</p>
 */
@RestController
@RequestMapping("/api/me/hotel-bookings")
@PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')")
@RequiredArgsConstructor
public class TenantHotelBookingHistoryController {

    private final TenantHotelBookingHistoryService historyService;

    @GetMapping
    public ResponseEntity<PagedApiResponse<MarketplaceBookingTenantDto>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MarketplaceBookingTenantDto> result =
                historyService.list(PageRequest.of(Math.max(page, 0), clamp(size)));
        return ResponseEntity.ok(PagedApiResponse.of(
                "Hotel bookings fetched", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Hotel booking fetched", historyService.get(publicId)));
    }

    /**
     * 404 until the platform has issued it — a document that does not exist is not a 403.
     *
     * <p>Serves the hotel's own file when one was uploaded, and the rendered PDF otherwise, so the
     * content type is whatever the document actually is rather than an assumption.</p>
     */
    @GetMapping("/{publicId}/voucher.pdf")
    public ResponseEntity<byte[]> voucher(@PathVariable UUID publicId) {
        MarketplaceVoucherService.VoucherDocument doc = historyService.voucherDocument(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + doc.fileName() + "\"")
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .body(doc.content());
    }

    private static int clamp(int size) {
        return size < 1 ? 20 : Math.min(size, 100);
    }
}
