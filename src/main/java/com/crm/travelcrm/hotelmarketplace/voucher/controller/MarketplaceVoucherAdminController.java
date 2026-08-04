package com.crm.travelcrm.hotelmarketplace.voucher.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingAdminDto;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import com.crm.travelcrm.hotelmarketplace.notification.MarketplaceNotifier;
import com.crm.travelcrm.hotelmarketplace.voucher.service.MarketplaceVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Voucher operations on a marketplace booking, platform side.
 *
 * <p>A separate controller from {@code MarketplaceBookingAdminController} rather than more methods on
 * it: the voucher is its own lifecycle (design §7 — {@code voucherStatus} is a second axis, not a
 * booking state), and Spring routes on the full path plus method, so sharing the base path costs
 * nothing.</p>
 *
 * <p>Cross-tenant by design; the {@code SUPER_ADMIN} role is the only gate. No step-up MFA — issuing
 * a document commits no money and moves no booking state, unlike approve/reject next door.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/marketplace/bookings")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class MarketplaceVoucherAdminController {

    private final MarketplaceVoucherService voucherService;
    private final MarketplaceBookingMapper mapper;
    private final MarketplaceNotifier notifier;

    @PostMapping("/{publicId}/voucher/issue")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> issue(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin) {
        PlatformHotelBooking row = voucherService.issue(publicId, superAdmin == null ? null : superAdmin.getId());
        // Notified from here rather than inside issue(): that method is transactional, and a
        // notification telling a tenant their voucher is ready must not be able to outrun the commit
        // that made it true.
        notifier.voucherIssued(row);
        return ResponseEntity.ok(ApiResponse.success("Voucher issued", mapper.toAdminDto(row)));
    }

    @PostMapping("/{publicId}/voucher/revoke")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> revoke(
            @PathVariable UUID publicId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal SuperAdmin superAdmin) {
        PlatformHotelBooking row = voucherService.revoke(publicId, reason,
                superAdmin == null ? null : superAdmin.getId());
        return ResponseEntity.ok(ApiResponse.success("Voucher revoked", mapper.toAdminDto(row)));
    }

    /**
     * The SuperAdmin's copy. Unlike the tenant route this does not require the voucher to be ISSUED —
     * an operator has to be able to read the document before committing to it, which is why the PDF
     * carries a "Preview - Not Issued" stamp until it is.
     */
    @GetMapping("/{publicId}/voucher.pdf")
    public ResponseEntity<byte[]> download(@PathVariable UUID publicId) {
        PlatformHotelBooking row = voucherService.requireForPlatform(publicId);
        log.info("SuperAdmin voucher download for marketplace booking {}", row.getBookingCode());
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=hotel-voucher-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(voucherService.renderPdf(row));
    }
}
