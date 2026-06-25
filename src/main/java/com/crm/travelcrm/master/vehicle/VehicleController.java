package com.crm.travelcrm.master.vehicle;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<VehicleResponseDTO>> createVehicle(
            @Valid @RequestBody VehicleRequestDTO request) {

        VehicleResponseDTO created = vehicleService.createVehicle(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vehicle created successfully", created, 201));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<VehicleResponseDTO>> getAllVehicles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<VehicleResponseDTO> vehiclePage =
                vehicleService.getAllVehicles(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Vehicles fetched successfully",
                        vehiclePage.getContent(),
                        PaginationMeta.from(vehiclePage, sortBy, sortDir)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<VehicleResponseDTO>> getVehicleByPublicId(
            @PathVariable UUID publicId) {

        VehicleResponseDTO vehicle = vehicleService.getVehicleByPublicId(publicId);
        return ResponseEntity.ok(ApiResponse.success("Vehicle fetched successfully", vehicle));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<VehicleResponseDTO>> updateVehicle(
            @PathVariable UUID publicId,
            @Valid @RequestBody VehicleRequestDTO request) {

        VehicleResponseDTO updated = vehicleService.updateVehicle(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Vehicle updated successfully", updated));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> deleteVehicle(@PathVariable UUID publicId) {
        vehicleService.deleteVehicle(publicId);
        return ResponseEntity.ok(ApiResponse.success("Vehicle deleted successfully"));
    }

    @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadVehicleImage(
            @RequestParam("file") MultipartFile file) {

        String imagePath = vehicleService.uploadVehicleImage(file);
        return ResponseEntity.ok(
                ApiResponse.success("Image uploaded successfully", Map.of("imagePath", imagePath)));
    }

    @GetMapping("/filter")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<VehicleResponseDTO>>> filterByType(
            @RequestParam String type) {

        List<VehicleResponseDTO> vehicles = vehicleService.filterByType(type);
        return ResponseEntity.ok(ApiResponse.success("Vehicles filtered successfully", vehicles));
    }

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<VehicleResponseDTO>>> searchVehicles(
            @RequestParam String q) {

        List<VehicleResponseDTO> vehicles = vehicleService.searchVehicles(q);
        return ResponseEntity.ok(ApiResponse.success("Vehicles searched successfully", vehicles));
    }
}