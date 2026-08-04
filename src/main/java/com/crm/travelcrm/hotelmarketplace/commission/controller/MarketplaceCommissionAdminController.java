package com.crm.travelcrm.hotelmarketplace.commission.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionAdjustmentRequest;
import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionEntryDto;
import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionSummaryDto;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType;
import com.crm.travelcrm.hotelmarketplace.commission.service.PlatformCommissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The platform earning ledger, for the SuperAdmin console (design doc §13).
 *
 * <p><b>There is no tenant counterpart to this controller and there must never be one.</b> Every row
 * this returns carries the supplier cost and the platform's margin on a tenant's booking.</p>
 *
 * <p>{@code /api/super-admin} is already in {@code ModuleAccessFilter}'s always-allowed set, so no
 * entitlement wiring is needed — the platform console must keep working for a tenant whose marketplace
 * add-on has lapsed, which is exactly when someone needs to look at their ledger.</p>
 */
@RestController
@RequestMapping("/api/super-admin/marketplace/commissions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class MarketplaceCommissionAdminController {

    private final PlatformCommissionService commissionService;

    @GetMapping
    public ResponseEntity<PagedApiResponse<CommissionEntryDto>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false)    Long tenantId,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    String entryType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        Page<CommissionEntryDto> result = commissionService.search(
                tenantId, parseStatus(status), parseEntryType(entryType), from, to,
                PageRequest.of(Math.max(page, 0), clamp(size)));

        return ResponseEntity.ok(PagedApiResponse.of(
                "Commission ledger fetched", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<CommissionSummaryDto>> summary(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(ApiResponse.success(
                "Platform earning summary", commissionService.summary(tenantId, from, to)));
    }

    /**
     * Append a manual correction to one booking's ledger.
     *
     * <p>Keyed by the BOOKING's publicId, not a ledger row's: a correction is a new row, and the row it
     * corrects may not exist — an accrual that was never written is exactly the kind of thing an
     * adjustment fixes.</p>
     */
    @PostMapping("/{bookingPublicId}/adjust")
    public ResponseEntity<ApiResponse<CommissionEntryDto>> adjust(
            @PathVariable UUID bookingPublicId,
            @Valid @RequestBody CommissionAdjustmentRequest request) {

        return ResponseEntity.ok(ApiResponse.success(
                "Adjustment recorded",
                commissionService.toDto(commissionService.adjust(
                        bookingPublicId,
                        request.getAmount(),
                        request.getReason(),
                        request.getReferenceSuffix()))));
    }

    /** Keyed by the LEDGER ROW's publicId — settlement is a claim about one specific entry. */
    @PostMapping("/{publicId}/settle")
    public ResponseEntity<ApiResponse<CommissionEntryDto>> settle(
            @PathVariable UUID publicId,
            @RequestParam(required = false) String reason) {

        return ResponseEntity.ok(ApiResponse.success(
                "Entry settled",
                commissionService.toDto(commissionService.markSettled(publicId, reason))));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Unknown/blank → null, meaning "all". Lenient, mirroring the marketplace booking queue. */
    private static CommissionEntryStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CommissionEntryStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CommissionEntryType parseEntryType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CommissionEntryType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clamp(int size) {
        return size < 1 ? 25 : Math.min(size, 200);
    }
}
