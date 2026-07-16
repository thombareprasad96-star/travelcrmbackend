package com.crm.travelcrm.lead.assignment.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.lead.assignment.dto.AssignmentRecommendationResponse;
import com.crm.travelcrm.lead.assignment.service.LeadAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only assignment recommendation for the create-lead form. Gated on {@code LEAD_CREATE} — only a
 * user who can create a lead needs it. The response tells the UI whether to force self-assignment
 * (agents/staff/sub-agents) or to pre-fill a load-based recommendation into an editable dropdown
 * (tenant admin / manager). Nothing is persisted here; the authoritative decision + audit happen on
 * {@code POST /api/leads}.
 */
@Slf4j
@RestController
@RequestMapping("/api/leads/assignment")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('LEAD_CREATE')")
public class LeadAssignmentController {

    private final LeadAssignmentService leadAssignmentService;

    @GetMapping("/recommendation")
    public ResponseEntity<ApiResponse<AssignmentRecommendationResponse>> getRecommendation() {
        return ResponseEntity.ok(ApiResponse.success(
                "Assignment recommendation fetched", leadAssignmentService.recommendForCreate()));
    }
}