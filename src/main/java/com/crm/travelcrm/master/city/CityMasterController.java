package com.crm.travelcrm.master.city;

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
@RequestMapping("/api/cities")
@RequiredArgsConstructor
public class CityMasterController {

    private final CityMasterService cityMasterService;

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<Void>> saveCity(
            @Valid @RequestBody CityMasterRequestDTO request) {
        cityMasterService.saveCity(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City saved successfully"));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CityMasterResponseDTO>> getAllCities(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<CityMasterResponseDTO> cityPage =
                cityMasterService.getAllCities(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Cities fetched successfully",
                        cityPage.getContent(),
                        PaginationMeta.from(cityPage, sortBy, sortDir)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CityMasterResponseDTO>> getCityById(
            @PathVariable Long id) {

        CityMasterResponseDTO city = cityMasterService.getCityById(id);
        return ResponseEntity.ok(ApiResponse.success("City fetched successfully", city));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<Void>> updateCity(
            @PathVariable Long id,
            @Valid @RequestBody CityMasterRequestDTO request) {

        cityMasterService.updateCity(id, request);
        return ResponseEntity.ok(ApiResponse.success("City updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<Void>> deleteCity(
            @PathVariable Long id) {

        cityMasterService.deleteCity(id);
        return ResponseEntity.ok(ApiResponse.success("City deleted successfully"));
    }
}