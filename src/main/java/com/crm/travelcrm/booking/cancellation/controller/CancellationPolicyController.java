package com.crm.travelcrm.booking.cancellation.controller;

import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyResponse;
import com.crm.travelcrm.booking.cancellation.dto.CreateCancellationPolicyRequest;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import com.crm.travelcrm.booking.cancellation.service.CancellationPolicyService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Manage tenant cancellation policies. Reads require BOOKING_READ (agents need to see the terms that
 * will govern a cancellation); writes require the elevated CANCELLATION_POLICY_MANAGE.
 */
@RestController
@RequestMapping("/api/cancellation-policies")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")   // class default; writes override below
public class CancellationPolicyController {

    private static final Logger log = LogManager.getLogger(CancellationPolicyController.class);

    private final CancellationPolicyService policyService;

    @PostMapping
    @PreAuthorize("hasAuthority('CANCELLATION_POLICY_MANAGE')")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> save(
            @Valid @RequestBody CreateCancellationPolicyRequest request) {
        log.info("POST /api/cancellation-policies - level: {}", request.getLevel());
        CancellationPolicyResponse saved = policyService.save(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Cancellation policy saved", saved, 201));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CancellationPolicyResponse>>> listActive() {
        return ResponseEntity.ok(ApiResponse.success("Cancellation policies fetched",
                policyService.listActive()));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<CancellationPolicyResponse>> getOne(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Cancellation policy fetched",
                policyService.getByPublicId(publicId)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<CancellationPolicyResponse>>> history(
            @RequestParam CancellationPolicyLevel level,
            @RequestParam(required = false) UUID ownerPublicId) {
        return ResponseEntity.ok(ApiResponse.success("Cancellation policy history fetched",
                policyService.history(level, ownerPublicId)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('CANCELLATION_POLICY_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        policyService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Cancellation policy retired"));
    }
}