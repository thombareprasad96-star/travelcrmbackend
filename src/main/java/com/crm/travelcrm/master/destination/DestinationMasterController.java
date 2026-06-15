package com.crm.travelcrm.master.destination;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/destinations")
@RequiredArgsConstructor
public class DestinationMasterController {

    private final DestinationMasterService destinationMasterService;

    // Only SUPERADMIN and TENANT_ADMIN may write destinations.
    //  - ROLE_SUPER_ADMIN → the SuperAdmin principal (creates GLOBAL destinations)
    //  - PLATFORM_ADMIN   → a User row with role SUPERADMIN, if one ever exists
    //  - USER_CREATE      → held only by TENANT_ADMIN (NOT by MANAGER/AGENT),
    //                       so it cleanly distinguishes the tenant admin from staff.
    // The previous gate (PLATFORM_ADMIN only) never matched the real SuperAdmin principal.
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'PLATFORM_ADMIN', 'USER_CREATE')")
    public ResponseEntity<ApiResponse<Void>> saveDestination(
            @Valid @RequestBody DestinationMasterRequestDTO request) {

        destinationMasterService.saveDestination(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Destination saved successfully"));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<DestinationMasterResponseDTO>> getAllDestinations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<DestinationMasterResponseDTO> destinationPage =
                destinationMasterService.getAllDestinations(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Destinations fetched successfully",
                        destinationPage.getContent(),
                        PaginationMeta.from(destinationPage, sortBy, sortDir)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DestinationMasterResponseDTO>> getDestinationById(
            @PathVariable Long id) {

        DestinationMasterResponseDTO destination =
                destinationMasterService.getDestinationById(id);
        return ResponseEntity.ok(ApiResponse.success("Destination fetched successfully", destination));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'PLATFORM_ADMIN', 'USER_CREATE')")
    public ResponseEntity<ApiResponse<Void>> updateDestination(
            @PathVariable Long id,
            @Valid @RequestBody DestinationMasterRequestDTO request) {

        destinationMasterService.updateDestination(id, request);
        return ResponseEntity.ok(ApiResponse.success("Destination updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN', 'PLATFORM_ADMIN', 'USER_CREATE')")
    public ResponseEntity<ApiResponse<Void>> deleteDestination(
            @PathVariable Long id) {

        destinationMasterService.deleteDestination(id);
        return ResponseEntity.ok(ApiResponse.success("Destination deleted successfully"));
    }
}