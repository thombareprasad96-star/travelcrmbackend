package com.crm.travelcrm.vendor.controller;

import com.crm.travelcrm.booking.controller.BookingController;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.vendor.dto.request.*;
import com.crm.travelcrm.vendor.dto.response.VendorResponseDTO;
import com.crm.travelcrm.vendor.dto.response.VendorStatsDTO;
import com.crm.travelcrm.vendor.enums.VendorPayStatus;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import com.crm.travelcrm.vendor.service.VendorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vendors")
@RequiredArgsConstructor
public class VendorController {

    private static final Logger log = LogManager.getLogger(VendorController.class);
    private final VendorService vendorService;

    // 1. GET ALL
//    @GetMapping
//    @PreAuthorize("isAuthenticated()")
//    public ResponseEntity<List<VendorResponseDTO>> getAll() {
//        return ResponseEntity.ok(vendorService.getAll());
//

    // Enum metadata for FE dropdowns — returns all valid status / pay-status values.
    @GetMapping("/statuses")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, List<String>>> getStatuses() {
        return ResponseEntity.ok(Map.of(
                "status", java.util.Arrays.stream(VendorStatus.values()).map(Enum::name).toList(),
                "payStatus", java.util.Arrays.stream(VendorPayStatus.values()).map(Enum::name).toList()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<VendorResponseDTO>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "vendorCode") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        log.info("Fetching vendors: page={}, size={}, sortBy={}, sortDir={}",
                page, size, sortBy, sortDir);

        PagedApiResponse<VendorResponseDTO> response = vendorService.getAll(page, size, sortBy, sortDir);
        return ResponseEntity.ok(response);
    }



// 2. GET BY ID
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponseDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(vendorService.getById(id));
    }

    // 3. GET BY CODE — must be before /{id} to avoid path conflict
    @GetMapping("/code/{code}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponseDTO> getByCode(@PathVariable String code) {
        return ResponseEntity.ok(vendorService.getByCode(code));
    }

    // 4. CREATE
    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<VendorResponseDTO> create(@Valid @RequestBody VendorRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vendorService.create(request));
    }

    // 5. UPDATE
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<VendorResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody VendorRequestDTO request) {
        return ResponseEntity.ok(vendorService.update(id, request));
    }

    // 6. UPDATE STATUS
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<VendorResponseDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody VendorStatusUpdateDTO request) {
        return ResponseEntity.ok(vendorService.updateStatus(id, request));
    }

    // 7. UPDATE PAYMENT
    @PatchMapping("/{id}/payment")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<VendorResponseDTO> updatePayment(
            @PathVariable Long id,
            @Valid @RequestBody VendorPaymentUpdateDTO request) {
        return ResponseEntity.ok(vendorService.updatePayment(id, request));
    }

    // 8. DELETE
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vendorService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // 9. FILTER
    @GetMapping("/filter")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<VendorResponseDTO>> filter(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String payStatus) {
        return ResponseEntity.ok(vendorService.filter(status, type, payStatus));
    }

    // 10. SEARCH
    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<VendorResponseDTO>> search(@RequestParam("q") String q) {
        return ResponseEntity.ok(vendorService.search(q));
    }

    // 11. GET BY TYPE
    @GetMapping("/type/{type}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<VendorResponseDTO>> getByType(@PathVariable String type) {
        return ResponseEntity.ok(vendorService.getByType(type));
    }

    // 12. STATS
    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorStatsDTO> getStats() {
        return ResponseEntity.ok(vendorService.getStats());
    }

    // 13. GET BOOKINGS
    @GetMapping("/{id}/bookings")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Map<String, Object>>> getBookings(@PathVariable Long id) {
        return ResponseEntity.ok(vendorService.getBookings(id));
    }

    // 14. RATE VENDOR
    @PostMapping("/{id}/rating")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VendorResponseDTO> rateVendor(
            @PathVariable Long id,
            @Valid @RequestBody VendorRatingDTO request) {
        return ResponseEntity.ok(vendorService.rateVendor(id, request));
    }

    // 15. EXPORT CSV — raw bytes, no ApiResponse wrapper
    @GetMapping("/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] csv = vendorService.exportCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vendors.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    // 16. SEND EMAIL
    @PostMapping("/{id}/send-email")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Map<String, String>> sendEmail(
            @PathVariable Long id,
            @Valid @RequestBody VendorEmailDTO request) {
        return ResponseEntity.ok(vendorService.sendEmail(id, request));
    }
}