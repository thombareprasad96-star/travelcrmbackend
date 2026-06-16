package com.crm.travelcrm.master.hotel.controller;

import com.crm.travelcrm.master.hotel.dto.request.HotelRequestDTO;
import com.crm.travelcrm.master.hotel.dto.request.MealPlanRequestDTO;
import com.crm.travelcrm.master.hotel.dto.request.RoomTypeRequestDTO;
import com.crm.travelcrm.master.hotel.dto.response.DestinationWithHotelsDTO;
import com.crm.travelcrm.master.hotel.dto.response.HotelResponseDTO;
import com.crm.travelcrm.master.hotel.dto.response.MealPlanResponseDTO;
import com.crm.travelcrm.master.hotel.dto.response.RoomTypeResponseDTO;
import com.crm.travelcrm.master.hotel.service.HotelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hotels")
@RequiredArgsConstructor
public class HotelController {

    private final HotelService hotelService;

    // 1. GET ALL — grouped by destination (raw array, no ApiResponse wrapper)
    @GetMapping("/")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DestinationWithHotelsDTO>> getAllHotels() {
        return ResponseEntity.ok(hotelService.getAllGroupedByDestination());
    }

    // 2. GET BY DESTINATION
    @GetMapping("/destination/{destinationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<HotelResponseDTO>> getByDestination(
            @PathVariable Long destinationId) {
        return ResponseEntity.ok(hotelService.getByDestination(destinationId));
    }

    // 3. GET BY ID
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<HotelResponseDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(hotelService.getById(id));
    }

    // 4. CREATE
    @PostMapping("/")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<HotelResponseDTO> create(
            @Valid @RequestBody HotelRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(hotelService.create(request));
    }

    // 5. UPDATE
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<HotelResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody HotelRequestDTO request) {
        return ResponseEntity.ok(hotelService.update(id, request));
    }

    // 6. DELETE
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        hotelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // 7. SET DEFAULT
    // NOTE: The frontend swaps args — the path variable carries the destination ID,
    // and the body field "destinationId" carries the actual hotel ID.
    @PatchMapping("/{pathVar}/set-default")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> setDefault(
            @PathVariable Long pathVar,
            @RequestBody Map<String, Long> body) {
        Long actualHotelId      = body.get("destinationId"); // frontend sends hotelId here
        Long actualDestinationId = pathVar;                  // frontend sends destinationId here
        hotelService.setDefault(actualHotelId, actualDestinationId);
        return ResponseEntity.ok().build();
    }

    // 8. UPLOAD HOTEL IMAGE
    @PostMapping("/upload-image")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<String> uploadHotelImage(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(hotelService.uploadHotelImage(file));
    }

    // ── ROOM TYPES ────────────────────────────────────────────────────────────

    // 9. ADD ROOM TYPE
    @PostMapping("/{hotelId}/room-types")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<RoomTypeResponseDTO> addRoomType(
            @PathVariable Long hotelId,
            @Valid @RequestBody RoomTypeRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(hotelService.addRoomType(hotelId, request));
    }

    // 10. UPDATE ROOM TYPE
    @PutMapping("/{hotelId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<RoomTypeResponseDTO> updateRoomType(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId,
            @Valid @RequestBody RoomTypeRequestDTO request) {
        return ResponseEntity.ok(hotelService.updateRoomType(hotelId, roomTypeId, request));
    }

    // 11. DELETE ROOM TYPE
    @DeleteMapping("/{hotelId}/room-types/{roomTypeId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> deleteRoomType(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId) {
        hotelService.deleteRoomType(hotelId, roomTypeId);
        return ResponseEntity.noContent().build();
    }

    // 12. UPLOAD ROOM IMAGES
    @PostMapping("/{hotelId}/room-types/{roomTypeId}/images")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<List<String>> uploadRoomImages(
            @PathVariable Long hotelId,
            @PathVariable Long roomTypeId,
            @RequestParam("files") List<MultipartFile> files) {
        return ResponseEntity.ok(hotelService.uploadRoomImages(hotelId, roomTypeId, files));
    }

    // ── MEAL PLANS ────────────────────────────────────────────────────────────

    // 13. ADD MEAL PLAN
    @PostMapping("/{hotelId}/meal-plans")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<MealPlanResponseDTO> addMealPlan(
            @PathVariable Long hotelId,
            @Valid @RequestBody MealPlanRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(hotelService.addMealPlan(hotelId, request));
    }

    // 14. UPDATE MEAL PLAN
    @PutMapping("/{hotelId}/meal-plans/{mealPlanId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<MealPlanResponseDTO> updateMealPlan(
            @PathVariable Long hotelId,
            @PathVariable Long mealPlanId,
            @Valid @RequestBody MealPlanRequestDTO request) {
        return ResponseEntity.ok(hotelService.updateMealPlan(hotelId, mealPlanId, request));
    }

    // 15. DELETE MEAL PLAN
    @DeleteMapping("/{hotelId}/meal-plans/{mealPlanId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> deleteMealPlan(
            @PathVariable Long hotelId,
            @PathVariable Long mealPlanId) {
        hotelService.deleteMealPlan(hotelId, mealPlanId);
        return ResponseEntity.noContent().build();
    }
}