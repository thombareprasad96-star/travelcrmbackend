package com.crm.travelcrm.master.geography.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.master.geography.dto.request.CreateCountryRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCountryRequest;
import com.crm.travelcrm.master.geography.dto.response.CountryDto;
import com.crm.travelcrm.master.geography.service.CountryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Country master endpoints. */
@Slf4j
@RestController
@RequestMapping("/api/v1/countries")
@RequiredArgsConstructor
public class CountryController {

    private final CountryService countryService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CountryDto>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(countryService.getAll(page, size, sortBy, sortDir));
    }

    @GetMapping("/{countryId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CountryDto>> getById(@PathVariable Long countryId) {
        return ResponseEntity.ok(ApiResponse.success("Country fetched", countryService.getById(countryId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN')")
    public ResponseEntity<ApiResponse<CountryDto>> create(@Valid @RequestBody CreateCountryRequest request) {
        CountryDto created = countryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Country created successfully", created, 201));
    }

    @PutMapping("/{countryId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CountryDto>> update(
            @PathVariable Long countryId,
            @Valid @RequestBody UpdateCountryRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Country updated successfully", countryService.update(countryId, request)));
    }

    @DeleteMapping("/{countryId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long countryId) {
        countryService.delete(countryId);
        return ResponseEntity.noContent().build();
    }
}