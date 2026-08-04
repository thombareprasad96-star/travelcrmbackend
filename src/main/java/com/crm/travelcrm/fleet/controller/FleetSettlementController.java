package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.fleet.dto.FleetCashDirectionDto;
import com.crm.travelcrm.fleet.dto.FleetCashEntryRequestDto;
import com.crm.travelcrm.fleet.dto.FleetSettlementResponseDto;
import com.crm.travelcrm.fleet.service.FleetSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Driver cash and trip settlement.
 *
 * <p><b>Permissions split money authority from operational authority.</b> Handing a driver an
 * advance and recording what came back is bookkeeping a manager or clerk does daily
 * ({@code FLEET_MONEY_READ} to see, {@code FLEET_UPDATE} to record). <b>Signing the sheet is
 * different</b> — it freezes the numbers and releases the driver, and it is irreversible except by a
 * dated reversal. That needs {@code FLEET_MONEY_SETTLE}, which no role inherits from managing the
 * fleet; it is granted deliberately per user.
 */
@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_MONEY_READ')")
public class FleetSettlementController {

    private final FleetSettlementService settlementService;

    /** Movement catalogue for the entry form. Gated on plain FLEET_READ — it carries no money. */
    @GetMapping("/cash-directions")
    @PreAuthorize("hasAuthority('FLEET_READ')")
    public ResponseEntity<ApiResponse<List<FleetCashDirectionDto>>> cashDirections() {
        return ResponseEntity.ok(ApiResponse.success("Cash directions fetched",
                settlementService.cashDirections()));
    }

    /** Advance, return, collection, deposit, recovery or adjustment. */
    @PostMapping("/cash")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetSettlementResponseDto>> recordCash(
            @Valid @RequestBody FleetCashEntryRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Cash movement recorded",
                        settlementService.recordCash(request), 201));
    }

    /** Every driver's sheet for a trip — one per man on a multi-driver trip. */
    @GetMapping("/trips/{tripPublicId}/settlements")
    public ResponseEntity<ApiResponse<List<FleetSettlementResponseDto>>> forTrip(
            @PathVariable UUID tripPublicId) {
        return ResponseEntity.ok(ApiResponse.success("Settlements fetched",
                settlementService.forTrip(tripPublicId)));
    }

    /** The unsquared worklist — whose cash is still out on the road. */
    @GetMapping("/settlements/open")
    public ResponseEntity<ApiResponse<List<FleetSettlementResponseDto>>> open() {
        return ResponseEntity.ok(ApiResponse.success("Open settlements fetched",
                settlementService.openSettlements()));
    }

    /** Recompute and mark reconciled — printable, not yet signed. */
    @PostMapping("/trips/{tripPublicId}/settlements/{driverPublicId}/reconcile")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetSettlementResponseDto>> reconcile(
            @PathVariable UUID tripPublicId, @PathVariable UUID driverPublicId) {
        return ResponseEntity.ok(ApiResponse.success("Settlement reconciled",
                settlementService.reconcile(tripPublicId, driverPublicId)));
    }

    /**
     * Sign off. Refuses unless the cash squares to exactly zero AND the driver has acknowledged —
     * the module's central invariant: a trip stays visibly open until the driver's cash is squared.
     */
    @PostMapping("/trips/{tripPublicId}/settlements/{driverPublicId}/settle")
    @PreAuthorize("hasAuthority('FLEET_MONEY_SETTLE')")
    public ResponseEntity<ApiResponse<FleetSettlementResponseDto>> settle(
            @PathVariable UUID tripPublicId, @PathVariable UUID driverPublicId,
            @RequestBody(required = false) Map<String, Boolean> body) {
        boolean acknowledged = body != null && Boolean.TRUE.equals(body.get("driverAcknowledged"));
        return ResponseEntity.ok(ApiResponse.success("Settlement signed off",
                settlementService.settle(tripPublicId, driverPublicId, acknowledged)));
    }

    /**
     * The printable hisaab. Rides the class-level FLEET_MONEY_READ — it carries the driver's whole
     * cash position, which is precisely what that grant exists to fence off.
     */
    @GetMapping("/trips/{tripPublicId}/settlements/{driverPublicId}/sheet")
    public ResponseEntity<byte[]> sheet(
            @PathVariable UUID tripPublicId, @PathVariable UUID driverPublicId) {
        byte[] pdf = settlementService.settlementSheet(tripPublicId, driverPublicId);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "inline; filename=settlement-" + driverPublicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
