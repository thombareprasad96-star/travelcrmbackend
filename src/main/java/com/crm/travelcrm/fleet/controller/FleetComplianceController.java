package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetDocumentRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDocumentResponseDto;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.service.FleetComplianceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compliance documents — the papers a check-post, an RTO or a border post asks for.
 *
 * <p>Rides the operational fleet permissions rather than the money ones: recording that an insurance
 * policy was renewed is a dispatcher's job, not an accountant's. Only the linked expense is money,
 * and that lives on the expense ledger with its own gate.
 */
@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")
public class FleetComplianceController {

    private final FleetComplianceService complianceService;

    /** Category catalogue for the entry form, so the frontend keeps no copy of the enum. */
    @GetMapping("/document-categories")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> categories() {
        List<Map<String, Object>> list = java.util.Arrays.stream(FleetDocumentCategory.values())
                .map(c -> Map.<String, Object>of(
                        "code", c.name(),
                        "label", c.label(),
                        "owner", c.owner().name(),
                        "blocksByDefault", c.blocksByDefault(),
                        "countryCode", c.countryCode() == null ? "" : c.countryCode(),
                        "needsState", c.needsState(),
                        "needsExitDeadline", c.needsExitDeadline()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Document categories fetched", list));
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetDocumentResponseDto>> create(
            @Valid @RequestBody FleetDocumentRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document recorded", complianceService.create(request), 201));
    }

    @PutMapping("/documents/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetDocumentResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetDocumentRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Document updated",
                complianceService.update(publicId, request)));
    }

    /** Insert the replacement, mark the original superseded. The old certificate is kept in full. */
    @PostMapping("/documents/{publicId}/renew")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetDocumentResponseDto>> renew(
            @PathVariable UUID publicId, @Valid @RequestBody FleetDocumentRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Document renewed",
                        complianceService.renew(publicId, request), 201));
    }

    @PostMapping("/documents/{publicId}/revoke")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetDocumentResponseDto>> revoke(
            @PathVariable UUID publicId, @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success("Document revoked",
                complianceService.revoke(publicId, body == null ? null : body.get("reason"))));
    }

    @DeleteMapping("/documents/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        complianceService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Document removed"));
    }

    @GetMapping("/vehicles/{publicId}/documents")
    public ResponseEntity<ApiResponse<List<FleetDocumentResponseDto>>> forVehicle(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Documents fetched",
                complianceService.forVehicle(publicId)));
    }

    @GetMapping("/drivers/{publicId}/documents")
    public ResponseEntity<ApiResponse<List<FleetDocumentResponseDto>>> forDriver(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Documents fetched",
                complianceService.forDriver(publicId)));
    }

    /**
     * Filtered list. The only route to a document that is neither expiring nor attached to an asset
     * already open — including the backfilled rows still waiting for someone to fill in their number
     * and issuing authority ({@code ?needsReview=true}).
     */
    @GetMapping("/documents")
    public ResponseEntity<PagedApiResponse<FleetDocumentResponseDto>> list(
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(complianceService.list(
                ownerType, vehicleId, driverId, category, status, needsReview, search, page, size));
    }

    @GetMapping("/documents/expiring")
    public ResponseEntity<ApiResponse<List<FleetDocumentResponseDto>>> expiring(
            @RequestParam(required = false) Integer withinDays) {
        return ResponseEntity.ok(ApiResponse.success("Expiring documents fetched",
                complianceService.expiring(withinDays)));
    }

    /**
     * Pre-dispatch check. {@code throughDate} should be the trip's RETURN date — a permit valid
     * tomorrow but expired on day six of a Char Dham run passes every "valid today" test and still
     * ends with the vehicle impounded at a barrier.
     */
    @GetMapping("/compliance-check")
    public ResponseEntity<ApiResponse<FleetComplianceService.ComplianceCheck>> check(
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate throughDate) {
        return ResponseEntity.ok(ApiResponse.success("Compliance checked",
                complianceService.check(vehicleId, driverId, throughDate)));
    }
}
