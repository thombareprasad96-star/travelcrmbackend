package com.crm.travelcrm.hotelmarketplace.booking.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.SubmitMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.service.MarketplaceBookingRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The tenant's hotel booking requests.
 *
 * <p>Under {@code /api/hotel-marketplace}, so {@code ModuleAccessFilter} gates the whole surface on
 * the {@code HOTEL_MARKETPLACE} add-on before any handler runs. Permissions are the second gate:
 * module answers "did this tenant buy it", permission answers "may this user".</p>
 *
 * <p>The verb is <b>request</b>, never confirm. Nothing this controller can reach puts a booking into
 * a confirmed state — only a SuperAdmin approval does that.</p>
 */
@RestController
@RequestMapping("/api/hotel-marketplace/bookings")
@RequiredArgsConstructor
public class MarketplaceBookingController {

    private final MarketplaceBookingRequestService requestService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> submit(
            @Valid @RequestBody SubmitMarketplaceBookingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Booking request submitted. Waiting for platform confirmation.",
                requestService.submit(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')")
    public ResponseEntity<PagedApiResponse<MarketplaceBookingTenantDto>> listMine(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String status) {
        Page<MarketplaceBookingTenantDto> result = requestService.listMine(
                PageRequest.of(Math.max(page, 0), clamp(size)), parseStatus(status));
        return ResponseEntity.ok(PagedApiResponse.of(
                "Booking requests fetched", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> getMine(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Booking request fetched", requestService.getMine(publicId)));
    }

    // ── Answering a revised price (design §8 Step 6B) ───────────────────────
    // Gated on BOOK rather than VIEW: accepting a revision commits the tenant to a larger payable,
    // which is the same act as ordering, done a second time at a different number.

    @PostMapping("/{publicId}/accept-revision")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> acceptRevision(
            @PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revised price accepted. Waiting for platform confirmation.",
                requestService.acceptRevision(publicId)));
    }

    @PostMapping("/{publicId}/decline-revision")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> declineRevision(
            @PathVariable UUID publicId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revised price declined. The request has been closed.",
                requestService.declineRevision(publicId, reason)));
    }

    /**
     * Withdraw a pending request, or ask the platform to cancel a confirmed one.
     *
     * <p>One endpoint for both because from the tenant's side it is one intention; the service picks
     * the transition from the booking's state, since only it knows whether a room is being held.</p>
     */
    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_CANCEL')")
    public ResponseEntity<ApiResponse<MarketplaceBookingTenantDto>> cancel(
            @PathVariable UUID publicId,
            @RequestParam(required = false) String reason) {
        MarketplaceBookingTenantDto result = requestService.cancel(publicId, reason);
        return ResponseEntity.ok(ApiResponse.success(
                result.getStatus() == MarketplaceBookingStatus.CANCEL_REQUESTED
                        ? "Cancellation requested. The platform will confirm the charge with the hotel."
                        : "Request withdrawn.",
                result));
    }

    /** Unknown or blank means "all". Lenient, mirroring the SuperAdmin queue. */
    private static MarketplaceBookingStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MarketplaceBookingStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clamp(int size) {
        return size < 1 ? 20 : Math.min(size, 100);
    }
}
