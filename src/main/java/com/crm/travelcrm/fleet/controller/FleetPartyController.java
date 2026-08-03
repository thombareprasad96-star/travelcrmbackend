package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetPartyRequestDto;
import com.crm.travelcrm.fleet.dto.FleetPartyResponseDto;
import com.crm.travelcrm.fleet.service.FleetPartyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The fleet's own directory of hired-vehicle owners, suppliers and garages.
 *
 * <p>Under {@code /api/fleet}, so it already rides the FLEET module entitlement and the fleet
 * permission family — a Fleet-only tenant reaches it, and a CRM tenant without the module does not.
 *
 * <p>Bank details live on these rows, so reads ride FLEET_READ rather than a money grant on
 * purpose: the dispatcher who assigns an attached vehicle needs to see whose it is and call them.
 * What is genuinely sensitive here is payout data, and paying anyone is a phase-6 concern that will
 * bring its own gate.
 */
@RestController
@RequestMapping("/api/fleet/parties")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")
public class FleetPartyController {

    private final FleetPartyService partyService;

    @PostMapping
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetPartyResponseDto>> create(
            @Valid @RequestBody FleetPartyRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Party added", partyService.create(request), 201));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<FleetPartyResponseDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(partyService.list(search, active, page, size));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<FleetPartyResponseDto>> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Party fetched",
                partyService.getByPublicId(publicId)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetPartyResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetPartyRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Party updated",
                partyService.update(publicId, request)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        partyService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Party removed"));
    }
}
