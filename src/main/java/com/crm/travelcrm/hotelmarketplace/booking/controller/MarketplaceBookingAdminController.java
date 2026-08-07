package com.crm.travelcrm.hotelmarketplace.booking.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ApproveMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.CancelMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingAdminDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.QuoteCancellationRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ReviseMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.booking.service.MarketplaceApprovalOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * The SuperAdmin queue: review, approve or reject tenant hotel booking requests.
 *
 * <p>Cross-tenant, platform realm. Approve and reject each require a step-up MFA code — they commit
 * the platform to a supplier and put a payable on a tenant's books.</p>
 */
@RestController
@RequestMapping("/api/super-admin/marketplace/bookings")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class MarketplaceBookingAdminController {

    private final PlatformHotelBookingRepository repository;
    private final MarketplaceApprovalOrchestrator orchestrator;
    private final MarketplaceBookingMapper mapper;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping
    public ResponseEntity<PagedApiResponse<MarketplaceBookingAdminDto>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    Long tenantId) {

        Page<PlatformHotelBooking> result = repository.searchForAdmin(
                parseStatus(status), tenantId, PageRequest.of(Math.max(page, 0), clamp(size)));

        return ResponseEntity.ok(PagedApiResponse.of(
                "Marketplace booking requests fetched",
                result.map(mapper::toAdminDto).getContent(),
                PaginationMeta.from(result)));
    }

    /**
     * Pending badge for the console sidebar — every state that is waiting on the platform to act.
     *
     * <p>{@code TENANT_APPROVAL_REQUIRED} is excluded because that one is waiting on the tenant, and
     * a badge that counts work somebody else owes is a badge operators learn to ignore.</p>
     */
    @GetMapping("/pending-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> pendingCount() {
        long count = repository.countByStatusAndDeletedAtIsNull(MarketplaceBookingStatus.REQUESTED)
                + repository.countByStatusAndDeletedAtIsNull(MarketplaceBookingStatus.TENANT_ACCEPTED)
                + repository.countByStatusAndDeletedAtIsNull(MarketplaceBookingStatus.CANCEL_REQUESTED);
        return ResponseEntity.ok(ApiResponse.success("Pending count", Map.of("count", count)));
    }

    @PostMapping("/{publicId}/review")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> review(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Marked under review", orchestrator.takeUnderReview(publicId)));
    }

    @PostMapping("/{publicId}/approve")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> approve(
            @PathVariable UUID publicId,
            @Valid @RequestBody ApproveMarketplaceBookingRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "approve a marketplace hotel booking", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Booking confirmed",
                orchestrator.approve(publicId, request, superAdmin == null ? null : superAdmin.getId())));
    }

    /**
     * Put a revised price to the tenant (design §8 Step 6B).
     *
     * <p>No step-up MFA, deliberately: this commits nobody to anything. It is the safe alternative to
     * approving a moved price silently, and putting friction on it would push operators towards the
     * dangerous action instead of away from it.</p>
     */
    @PostMapping("/{publicId}/request-revision")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> requestRevision(
            @PathVariable UUID publicId,
            @Valid @RequestBody ReviseMarketplaceBookingRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin) {
        return ResponseEntity.ok(ApiResponse.success(
                "Revised price sent to the tenant",
                orchestrator.requestRevision(publicId, request,
                        superAdmin == null ? null : superAdmin.getId())));
    }

    /**
     * Put the cancellation charge to the tenant (design §9 clauses 1-3). <b>The normal path.</b>
     *
     * <p>No step-up MFA, for the same reason {@code request-revision} has none: it commits nobody to
     * anything. The tenant's acceptance is what ends the booking, and the figure they accept is the
     * figure that binds — there is no later step in which it can change.</p>
     */
    @PostMapping("/{publicId}/quote-cancellation")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> quoteCancellation(
            @PathVariable UUID publicId,
            @Valid @RequestBody QuoteCancellationRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cancellation quote sent to the tenant",
                orchestrator.quoteCancellation(publicId, request,
                        superAdmin == null ? null : superAdmin.getId())));
    }

    /**
     * Cancel WITHOUT the tenant accepting a quote. The override, not the normal path.
     *
     * <p>Step-up MFA required: it ends a booking and decides what the tenant is refunded, with no
     * consent step in front of it. Reach for {@code quote-cancellation} unless the cancellation is
     * genuinely not the tenant's decision — the hotel closed, the supplier cancelled on us, a
     * booking has to be unwound for fraud.</p>
     */
    @PostMapping("/{publicId}/cancel")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> cancel(
            @PathVariable UUID publicId,
            @Valid @RequestBody CancelMarketplaceBookingRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "cancel a marketplace hotel booking", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Booking cancelled",
                orchestrator.cancel(publicId, request, superAdmin == null ? null : superAdmin.getId())));
    }

    @PostMapping("/{publicId}/reject")
    public ResponseEntity<ApiResponse<MarketplaceBookingAdminDto>> reject(
            @PathVariable UUID publicId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "reject a marketplace hotel booking", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Booking rejected",
                orchestrator.reject(publicId, reason, superAdmin == null ? null : superAdmin.getId())));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }

    /** Unknown/blank → null, meaning "all". Lenient, mirroring the upgrade-request queue. */
    private static MarketplaceBookingStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MarketplaceBookingStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clamp(int size) {
        return size < 1 ? 25 : Math.min(size, 200);
    }
}
