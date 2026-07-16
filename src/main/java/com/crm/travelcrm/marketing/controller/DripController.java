package com.crm.travelcrm.marketing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.CreateDripSequenceRequest;
import com.crm.travelcrm.marketing.dto.DripEnrollmentResponse;
import com.crm.travelcrm.marketing.dto.DripSequenceResponse;
import com.crm.travelcrm.marketing.dto.UpdateDripSequenceRequest;
import com.crm.travelcrm.marketing.enums.DripStatus;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import com.crm.travelcrm.marketing.service.DripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Multi-step drip sequences. Class default is {@code MARKETING_READ}; mutating routes override
 * to CREATE/UPDATE/DELETE and the lifecycle actions (activate/pause) require {@code MARKETING_SEND}.
 * Enrollment + step advancement is driven by {@code DripRunnerScheduler}.
 */
@RestController
@RequestMapping("/api/marketing/drips")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MARKETING_READ')")   // class default; mutating methods override below
public class DripController {

    private final DripService dripService;

    @GetMapping
    public PagedApiResponse<DripSequenceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) DripStatus status) {
        return dripService.list(page, size, sortBy, sortDir, search, status);
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<DripSequenceResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(dripService.get(publicId));
    }

    @GetMapping("/{publicId}/enrollments")
    public PagedApiResponse<DripEnrollmentResponse> enrollments(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) EnrollmentStatus status) {
        return dripService.enrollments(publicId, page, size, status);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MARKETING_CREATE')")
    public ResponseEntity<ApiResponse<DripSequenceResponse>> create(@Valid @RequestBody CreateDripSequenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dripService.create(request));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_UPDATE')")
    public ResponseEntity<ApiResponse<DripSequenceResponse>> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateDripSequenceRequest request) {
        return ResponseEntity.ok(dripService.update(publicId, request));
    }

    @PostMapping("/{publicId}/activate")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<DripSequenceResponse>> activate(@PathVariable UUID publicId) {
        return ResponseEntity.ok(dripService.activate(publicId));
    }

    @PostMapping("/{publicId}/pause")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<DripSequenceResponse>> pause(@PathVariable UUID publicId) {
        return ResponseEntity.ok(dripService.pause(publicId));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(dripService.delete(publicId));
    }
}