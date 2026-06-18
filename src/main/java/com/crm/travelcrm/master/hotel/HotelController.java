package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final HotelService hotelService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<HotelDto>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(hotelService.getAll(page, size, sortBy, sortDir));
    }

    @GetMapping("/destination/{destinationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<HotelDto>> getByDestination(
            @PathVariable Long destinationId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(hotelService.getByDestination(destinationId, page, size, sortBy, sortDir));
    }

    @GetMapping("/city/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<HotelDto>> getByCity(
            @PathVariable Long cityId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(hotelService.getByCity(cityId, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<HotelDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Hotel fetched", hotelService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<HotelDto>> create(@Valid @RequestBody CreateHotelRequest request) {
        HotelDto created = hotelService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Hotel created successfully", created, 201));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<HotelDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateHotelRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Hotel updated successfully", hotelService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        hotelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/set-default")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<HotelDto>> setDefault(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Hotel set as default", hotelService.setDefault(id)));
    }

    @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(@RequestParam("file") MultipartFile file) {
        String url = hotelService.uploadImage(file);
        return ResponseEntity.ok(ApiResponse.success("Image uploaded", Map.of("imagePath", url)));
    }

    // Room type endpoints
    @PostMapping("/{hotelId}/room-types")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<RoomTypeDto>> addRoomType(
            @PathVariable Long hotelId,
            @Valid @RequestBody CreateRoomTypeRequest request) {
        RoomTypeDto dto = hotelService.addRoomType(hotelId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Room type added", dto, 201));
    }

    @PutMapping("/{hotelId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<RoomTypeDto>> updateRoomType(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId,
            @Valid @RequestBody UpdateRoomTypeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Room type updated", hotelService.updateRoomType(hotelId, roomTypeId, request)));
    }

    @DeleteMapping("/{hotelId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> deleteRoomType(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId) {
        hotelService.deleteRoomType(hotelId, roomTypeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{hotelId}/room-types/{roomTypeId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<String>> uploadRoomImages(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId,
            @RequestParam("files") MultipartFile[] files) {
        return ResponseEntity.ok(hotelService.uploadRoomImages(hotelId, roomTypeId, files));
    }

    // Meal plan endpoints
    @PostMapping("/{hotelId}/meal-plans")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<MealPlanDto>> addMealPlan(
            @PathVariable Long hotelId,
            @Valid @RequestBody CreateMealPlanRequest request) {
        MealPlanDto dto = hotelService.addMealPlan(hotelId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Meal plan added", dto, 201));
    }

    @PutMapping("/{hotelId}/meal-plans/{mealPlanId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<MealPlanDto>> updateMealPlan(
            @PathVariable Long hotelId,
            @PathVariable Long mealPlanId,
            @Valid @RequestBody UpdateMealPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Meal plan updated", hotelService.updateMealPlan(hotelId, mealPlanId, request)));
    }

    @DeleteMapping("/{hotelId}/meal-plans/{mealPlanId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> deleteMealPlan(
            @PathVariable Long hotelId,
            @PathVariable Long mealPlanId) {
        hotelService.deleteMealPlan(hotelId, mealPlanId);
        return ResponseEntity.noContent().build();
    }
}