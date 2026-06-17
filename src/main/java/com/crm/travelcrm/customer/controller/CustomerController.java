package com.crm.travelcrm.customer.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.customer.dto.request.CreateCustomerRequest;
import com.crm.travelcrm.customer.dto.request.StatusUpdateRequest;
import com.crm.travelcrm.customer.dto.request.TierUpdateRequest;
import com.crm.travelcrm.customer.dto.request.UpdateCustomerRequest;
import com.crm.travelcrm.customer.dto.response.CustomerBookingResponse;
import com.crm.travelcrm.customer.dto.response.CustomerResponse;
import com.crm.travelcrm.customer.dto.response.CustomerStatsResponse;
import com.crm.travelcrm.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for tenant customers, consumed by {@code customerService.js}.
 *
 * <p>Every payload is wrapped in {@link ApiResponse} (single item) or
 * {@link PagedApiResponse} (list) per the project convention — the CSV export is
 * the only raw-bytes exception. Auth mirrors the sibling {@code vendor} module:
 * reads require any authenticated principal; writes require an admin authority.
 * Literal sub-paths are declared before {@code /{id}} so UUID matching can't
 * shadow them.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    // ── List (paginated) ─────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CustomerResponse>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {

        return ResponseEntity.ok(customerService.getAll(page, size, sortBy, sortDir));
    }

    // ── Search / filter / stats / export (literal paths first) ──────────────────

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CustomerResponse>> searchByPhone(@RequestParam String phone) {
        return ResponseEntity.ok(
                ApiResponse.success("Customer found", customerService.searchByPhone(phone)));
    }

    @GetMapping("/search-name")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>> searchByName(@RequestParam String name) {
        return ResponseEntity.ok(
                ApiResponse.success("Customers fetched", customerService.searchByName(name)));
    }

    @GetMapping("/filter")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>> filter(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String tier) {
        return ResponseEntity.ok(
                ApiResponse.success("Customers filtered", customerService.filter(status, type, tier)));
    }

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CustomerStatsResponse>> getStats() {
        return ResponseEntity.ok(
                ApiResponse.success("Customer stats fetched", customerService.getStats()));
    }

    @GetMapping("/export")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] csvData = customerService.exportCsv();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename("customers.csv").build());

        return ResponseEntity.ok().headers(headers).body(csvData);
    }

    // ── Item reads ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CustomerResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(
                ApiResponse.success("Customer fetched", customerService.getById(id)));
    }

    @GetMapping("/{id}/bookings")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CustomerBookingResponse>>> getBookingHistory(
            @PathVariable UUID id) {
        return ResponseEntity.ok(
                ApiResponse.success("Booking history fetched", customerService.getBookingHistory(id)));
    }

    // ── Mutations ───────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @Valid @RequestBody CreateCustomerRequest request) {

        log.info("Create customer request received");
        CustomerResponse response = customerService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Customer created successfully", response, 201));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CustomerResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Customer updated successfully",
                        customerService.update(id, request)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Customer status updated successfully",
                        customerService.updateStatus(id, request)));
    }

    @PatchMapping("/{id}/tier")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateTier(
            @PathVariable UUID id,
            @Valid @RequestBody TierUpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Customer tier updated successfully",
                        customerService.updateTier(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        customerService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Customer deleted successfully"));
    }
}