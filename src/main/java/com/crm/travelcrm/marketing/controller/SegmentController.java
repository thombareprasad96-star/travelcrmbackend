package com.crm.travelcrm.marketing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.CreateSegmentRequest;
import com.crm.travelcrm.marketing.dto.SegmentFieldDef;
import com.crm.travelcrm.marketing.dto.SegmentMemberResponse;
import com.crm.travelcrm.marketing.dto.SegmentPreviewRequest;
import com.crm.travelcrm.marketing.dto.SegmentPreviewResponse;
import com.crm.travelcrm.marketing.dto.SegmentResponse;
import com.crm.travelcrm.marketing.dto.UpdateSegmentRequest;
import com.crm.travelcrm.marketing.service.SegmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Customer segments — a saved query-builder over the tenant's customers. Class default is
 * {@code MARKETING_READ}; mutating routes override to CREATE/UPDATE/DELETE. Keyed by
 * {@code publicId} (UUID). Row scope (sub-agent) is enforced in the service via {@code SubAgentScope}.
 */
@RestController
@RequestMapping("/api/marketing/segments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MARKETING_READ')")   // class default; mutating methods override below
public class SegmentController {

    private final SegmentService segmentService;

    /** Field catalog that drives the query builder. Literal path — declared before /{publicId}. */
    @GetMapping("/fields")
    public ResponseEntity<ApiResponse<List<SegmentFieldDef>>> fields() {
        return ResponseEntity.ok(ApiResponse.success("Segment fields fetched successfully", segmentService.fields()));
    }

    @GetMapping
    public PagedApiResponse<SegmentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search) {
        return segmentService.list(page, size, sortBy, sortDir, search);
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<SegmentPreviewResponse>> preview(
            @Valid @RequestBody SegmentPreviewRequest request) {
        return ResponseEntity.ok(segmentService.preview(request));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<SegmentResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(segmentService.get(publicId));
    }

    @GetMapping("/{publicId}/members")
    public PagedApiResponse<SegmentMemberResponse> members(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return segmentService.members(publicId, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MARKETING_CREATE')")
    public ResponseEntity<ApiResponse<SegmentResponse>> create(@Valid @RequestBody CreateSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segmentService.create(request));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_UPDATE')")
    public ResponseEntity<ApiResponse<SegmentResponse>> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateSegmentRequest request) {
        return ResponseEntity.ok(segmentService.update(publicId, request));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(segmentService.delete(publicId));
    }
}