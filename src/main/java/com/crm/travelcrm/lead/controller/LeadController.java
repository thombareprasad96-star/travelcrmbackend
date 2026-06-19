package com.crm.travelcrm.lead.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    public ResponseEntity<ApiResponse<LeadResponseDto>> createLead(
            @Valid @RequestBody CreateLeadRequestDto request) {

        log.info("Received create lead request for: {}", request.getEmail());
        LeadResponseDto response = leadService.createLead(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lead created successfully", response, 201));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<LeadResponseDto>> searchLead(
            @RequestParam String keyword) {

        LeadResponseDto response = leadService.searchLead(keyword);
        return ResponseEntity.ok(ApiResponse.success("Lead found", response));
    }

    /** Fetch a single lead by its publicId (UUID). */
    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<LeadResponseDto>> getLeadById(
            @PathVariable UUID publicId) {

        LeadResponseDto response = leadService.getLeadById(publicId);
        return ResponseEntity.ok(ApiResponse.success("Lead fetched successfully", response));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<LeadResponseDto>> getAllLeads(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "10")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {

        Page<LeadResponseDto> leadPage =
                leadService.getAllLeads(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Leads fetched successfully",
                        leadPage.getContent(),
                        PaginationMeta.from(leadPage, sortBy, sortDir)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResponse<LeadResponseDto>> updateLead(
            @PathVariable UUID publicId,
            @Valid @RequestBody CreateLeadRequestDto request) {

        log.info("Received update lead request for publicId: {}", publicId);
        LeadResponseDto response = leadService.updateLead(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Lead updated successfully", response));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResponse<Void>> deleteLead(@PathVariable UUID publicId) {

        log.info("Received delete lead request for publicId: {}", publicId);
        leadService.deleteLead(publicId);
        return ResponseEntity.ok(ApiResponse.success("Lead deleted successfully"));
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /** Total live leads assigned to one user. */
    @GetMapping("/stats/users/{userPublicId}/count")
    public ResponseEntity<ApiResponse<Long>> getLeadCountForUser(
            @PathVariable UUID userPublicId) {

        long count = leadService.getLeadCountForUser(userPublicId);
        return ResponseEntity.ok(ApiResponse.success("Lead count fetched", count));
    }

    /** Workload dashboard: every active tenant user with their lead total. */
    @GetMapping("/stats/workload")
    public ResponseEntity<ApiResponse<List<UserWorkloadDto>>> getUserWorkload() {
        return ResponseEntity.ok(
                ApiResponse.success("User workload fetched", leadService.getUserWorkload()));
    }

    /** Lead count per (user, stage) pair. */
    @GetMapping("/stats/by-stage")
    public ResponseEntity<ApiResponse<List<UserLeadStageCountDto>>> getLeadsByStagePerUser() {
        return ResponseEntity.ok(
                ApiResponse.success("Stage breakdown fetched",
                        leadService.getLeadStageBreakdownPerUser()));
    }
}