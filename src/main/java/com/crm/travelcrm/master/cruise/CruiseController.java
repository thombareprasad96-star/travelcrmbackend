package com.crm.travelcrm.master.cruise;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cruises")
@RequiredArgsConstructor
public class CruiseController {

    private final CruiseService cruiseService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CruiseDto>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(cruiseService.getAll(page, size, sortBy, sortDir));
    }

    @GetMapping("/city/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CruiseDto>> getByCity(
            @PathVariable Long cityId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(cruiseService.getByCity(cityId, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CruiseDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Cruise fetched", cruiseService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CruiseDto>> create(@Valid @RequestBody CreateCruiseRequest request) {
        CruiseDto created = cruiseService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Cruise created", created, 201));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CruiseDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCruiseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cruise updated", cruiseService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        cruiseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Room type endpoints
    @PostMapping("/{cruiseId}/room-types")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CruiseRoomTypeDto>> addRoomType(
            @PathVariable Long cruiseId,
            @Valid @RequestBody CreateCruiseRoomTypeRequest request) {
        CruiseRoomTypeDto dto = cruiseService.addRoomType(cruiseId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Room type added", dto, 201));
    }

    @PutMapping("/{cruiseId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CruiseRoomTypeDto>> updateRoomType(
            @PathVariable Long cruiseId,
            @PathVariable Long roomTypeId,
            @Valid @RequestBody UpdateCruiseRoomTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Room type updated",
                cruiseService.updateRoomType(cruiseId, roomTypeId, request)));
    }

    @DeleteMapping("/{cruiseId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> deleteRoomType(
            @PathVariable Long cruiseId,
            @PathVariable Long roomTypeId) {
        cruiseService.deleteRoomType(cruiseId, roomTypeId);
        return ResponseEntity.noContent().build();
    }
}