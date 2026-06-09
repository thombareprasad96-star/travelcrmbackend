package com.crm.travelcrm.lead.controller;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.lead.dto.ApiResponseDto;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    @PostMapping
    public ResponseEntity<ApiResponseDto<LeadResponseDto>> createLead(
            @Valid @RequestBody CreateLeadRequestDto request) {

        log.info("Received create lead request for: {}", request.getEmail());
        LeadResponseDto response = leadService.createLead(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Lead created successfully", response));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponseDto<LeadResponseDto>> searchLead(
            @RequestParam String keyword) {           // ← was "phone", now generic keyword

        LeadResponseDto response = leadService.searchLead(keyword);
        return ResponseEntity.ok(
                ApiResponseDto.success("Lead found", response));
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

    @PutMapping("/{publicId}")                        // ← was /{leadId}
    public ResponseEntity<ApiResponseDto<LeadResponseDto>> updateLead(
            @PathVariable UUID publicId,              // ← was Long leadId
            @Valid @RequestBody CreateLeadRequestDto request) {

        log.info("Received update lead request for publicId: {}", publicId);
        LeadResponseDto response = leadService.updateLead(publicId, request);
        return ResponseEntity.ok(
                ApiResponseDto.success("Lead updated successfully", response));
    }

    @DeleteMapping("/{publicId}")                     // ← was /{leadId}
    public ResponseEntity<ApiResponseDto<Void>> deleteLead(
            @PathVariable UUID publicId) {            // ← was Long leadId

        log.info("Received delete lead request for publicId: {}", publicId);
        leadService.deleteLead(publicId);
        return ResponseEntity.ok(
                ApiResponseDto.success("Lead deleted successfully", null));
    }
}