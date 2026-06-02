package com.crm.travelcrm.lead.controller;

import com.crm.travelcrm.lead.dto.ApiResponse;
import com.crm.travelcrm.lead.dto.CreateLeadRequest;
import com.crm.travelcrm.lead.dto.LeadResponse;
import com.crm.travelcrm.lead.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;

@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;

    /**
     * POST /api/v1/leads
     * Creates a new lead with itinerary and services.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<LeadResponse>> createLead(
            @Valid @RequestBody CreateLeadRequest request) {

        log.info("Received create lead request for: {}", request.getEmail());
        LeadResponse response = leadService.createLead(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lead created successfully", response));
    }
    @GetMapping("/search")
    public LeadResponse searchLead(
            @RequestParam String phone) {
        return leadService.searchLead(phone);
    }
    @GetMapping
    public ResponseEntity<ApiResponse<Page<LeadResponse>>> getAllLeads(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<LeadResponse> leads =
                leadService.getAllLeads(
                        page,
                        size,
                        sortBy,
                        sortDir);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "Leads fetched successfully",
                        leads));
    }
}