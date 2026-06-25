package com.crm.travelcrm.master.airline;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/airlines")
@RequiredArgsConstructor
public class AirlineController {

    private final AirlineService airlineService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<AirlineDto>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(airlineService.getAll(page, size, sortBy, sortDir));
    }

    @GetMapping("/city/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<AirlineDto>> getByCity(
            @PathVariable Long cityId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(airlineService.getByCity(cityId, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AirlineDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Airline fetched", airlineService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<AirlineDto>> create(@Valid @RequestBody CreateAirlineRequest request) {
        AirlineDto created = airlineService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Airline created", created, 201));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<AirlineDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAirlineRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Airline updated", airlineService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        airlineService.delete(id);
        return ResponseEntity.noContent().build();
    }
}