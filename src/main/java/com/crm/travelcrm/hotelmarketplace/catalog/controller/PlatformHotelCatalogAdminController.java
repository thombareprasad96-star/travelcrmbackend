package com.crm.travelcrm.hotelmarketplace.catalog.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.*;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import com.crm.travelcrm.hotelmarketplace.catalog.service.PlatformHotelCatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * SuperAdmin management of the global hotel catalog.
 *
 * <p>Mounted under {@code /api/super-admin} rather than the {@code /api/platform} the design doc
 * sketched: that is the prefix the console client already talks to, it is already in
 * {@code ModuleAccessFilter.ALWAYS_ALLOWED} (platform traffic carries no {@code TenantContext}, so
 * the entitlement filter skips it), and a brand-new {@code /api} prefix would fail
 * {@code ModuleAccessCoverageTest} until it was classified.</p>
 *
 * <p>Publish and unpublish require a step-up MFA code, the same as a plan change: they are the two
 * actions that change what every tenant on the platform can buy.</p>
 */
@RestController
// Under `marketplace/` with the booking queue and the earning ledger, not beside them: one feature,
// one prefix. It sat at /api/super-admin/hotel-catalog while everything else in the module lived at
// /api/super-admin/marketplace/**, which was the code drifting from itself rather than a decision.
@RequestMapping("/api/super-admin/marketplace")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlatformHotelCatalogAdminController {

    private final PlatformHotelCatalogService catalogService;
    private final SuperAdminStepUpService superAdminStepUpService;

    // ── Hotels ──────────────────────────────────────────────────────────────

    @GetMapping("/hotels")
    public ResponseEntity<PagedApiResponse<PlatformHotelAdminDto>> list(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "25")   int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc")  String sortDir,
            @RequestParam(required = false)      String status,
            @RequestParam(required = false)      String q) {

        Sort sort = Sort.by("desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC, sortBy);
        Page<PlatformHotelAdminDto> result = catalogService.search(
                parseStatus(status), q, PageRequest.of(Math.max(page, 0), clamp(size), sort));

        return ResponseEntity.ok(PagedApiResponse.of(
                "Catalog hotels fetched", result.getContent(),
                PaginationMeta.from(result, sortBy, sortDir)));
    }

    @GetMapping("/hotels/{publicId}")
    public ResponseEntity<ApiResponse<PlatformHotelAdminDto>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Catalog hotel fetched", catalogService.get(publicId)));
    }

    @PostMapping("/hotels")
    public ResponseEntity<ApiResponse<PlatformHotelAdminDto>> create(
            @Valid @RequestBody PlatformHotelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Catalog hotel created", catalogService.create(request)));
    }

    @PutMapping("/hotels/{publicId}")
    public ResponseEntity<ApiResponse<PlatformHotelAdminDto>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody PlatformHotelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Catalog hotel updated", catalogService.update(publicId, request)));
    }

    /** Makes the hotel visible to every entitled tenant. Step-up guarded. */
    @PostMapping("/hotels/{publicId}/publish")
    public ResponseEntity<ApiResponse<PlatformHotelAdminDto>> publish(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "publish a platform hotel", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Hotel published", catalogService.publish(publicId)));
    }

    /** Withdraws from sale. Existing tenant projections survive as SOURCE_INACTIVE. Step-up guarded. */
    @PostMapping("/hotels/{publicId}/unpublish")
    public ResponseEntity<ApiResponse<PlatformHotelAdminDto>> unpublish(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "INACTIVE") String target,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "unpublish a platform hotel", httpRequest);
        PlatformHotelStatus to = parseStatus(target);
        return ResponseEntity.ok(ApiResponse.success(
                "Hotel unpublished",
                catalogService.unpublish(publicId, to == null ? PlatformHotelStatus.INACTIVE : to, reason)));
    }

    @DeleteMapping("/hotels/{publicId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        catalogService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Catalog hotel deleted"));
    }

    // ── Rooms ───────────────────────────────────────────────────────────────

    @PostMapping("/hotels/{hotelPublicId}/rooms")
    public ResponseEntity<ApiResponse<PlatformRoomDto>> addRoom(
            @PathVariable UUID hotelPublicId, @Valid @RequestBody PlatformRoomRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Room added", catalogService.addRoom(hotelPublicId, request)));
    }

    @PutMapping("/hotels/{hotelPublicId}/rooms/{roomPublicId}")
    public ResponseEntity<ApiResponse<PlatformRoomDto>> updateRoom(
            @PathVariable UUID hotelPublicId, @PathVariable UUID roomPublicId,
            @Valid @RequestBody PlatformRoomRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Room updated", catalogService.updateRoom(hotelPublicId, roomPublicId, request)));
    }

    @DeleteMapping("/hotels/{hotelPublicId}/rooms/{roomPublicId}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(
            @PathVariable UUID hotelPublicId, @PathVariable UUID roomPublicId) {
        catalogService.deleteRoom(hotelPublicId, roomPublicId);
        return ResponseEntity.ok(ApiResponse.success("Room removed"));
    }

    // ── Meal plans ──────────────────────────────────────────────────────────

    @PostMapping("/hotels/{hotelPublicId}/meal-plans")
    public ResponseEntity<ApiResponse<PlatformMealPlanDto>> addMealPlan(
            @PathVariable UUID hotelPublicId, @Valid @RequestBody PlatformMealPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Meal plan added", catalogService.addMealPlan(hotelPublicId, request)));
    }

    @PutMapping("/hotels/{hotelPublicId}/meal-plans/{mealPlanPublicId}")
    public ResponseEntity<ApiResponse<PlatformMealPlanDto>> updateMealPlan(
            @PathVariable UUID hotelPublicId, @PathVariable UUID mealPlanPublicId,
            @Valid @RequestBody PlatformMealPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Meal plan updated",
                catalogService.updateMealPlan(hotelPublicId, mealPlanPublicId, request)));
    }

    @DeleteMapping("/hotels/{hotelPublicId}/meal-plans/{mealPlanPublicId}")
    public ResponseEntity<ApiResponse<Void>> deleteMealPlan(
            @PathVariable UUID hotelPublicId, @PathVariable UUID mealPlanPublicId) {
        catalogService.deleteMealPlan(hotelPublicId, mealPlanPublicId);
        return ResponseEntity.ok(ApiResponse.success("Meal plan removed"));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }

    /** Unknown/blank → null, meaning "all". Lenient by design, mirroring the upgrade-request queue. */
    private static PlatformHotelStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PlatformHotelStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clamp(int size) {
        return size < 1 ? 25 : Math.min(size, 200);
    }
}
