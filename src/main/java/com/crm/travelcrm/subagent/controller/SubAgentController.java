package com.crm.travelcrm.subagent.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.subagent.dto.CreateSubAgentRequest;
import com.crm.travelcrm.subagent.dto.SubAgentCommissionLedgerDto;
import com.crm.travelcrm.subagent.dto.SubAgentResponse;
import com.crm.travelcrm.subagent.dto.SubAgentRollupRow;
import com.crm.travelcrm.subagent.dto.UpdateSubAgentRequest;
import com.crm.travelcrm.subagent.service.SubAgentRollupService;
import com.crm.travelcrm.subagent.service.SubAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * B2B franchise sub-agent management. Gated on USER_* authorities, which only the TENANT_ADMIN
 * holds — sub-agents themselves carry NO USER_* authority (and no CRM_FULL), so they can never
 * reach this API. Create mints the sub-agent User (role SUB_AGENT) + its markup/branding profile
 * together. Keyed by the profile's publicId.
 */
@RestController
@RequestMapping("/api/subagents")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('USER_READ')")   // class default; mutating methods override below
public class SubAgentController {

    private final SubAgentService subAgentService;
    private final SubAgentRollupService rollupService;

    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<SubAgentResponse>> create(
            @Valid @RequestBody CreateSubAgentRequest request) {
        SubAgentResponse res = subAgentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Sub-agent created successfully", res, 201));
    }

    /** Parent roll-up: every sub-agent's bookings + commission earned. Literal path wins over /{publicId}. */
    @GetMapping("/rollup")
    public ResponseEntity<ApiResponse<List<SubAgentRollupRow>>> rollup() {
        return ResponseEntity.ok(
                ApiResponse.success("Sub-agent roll-up fetched", rollupService.rollup()));
    }

    @GetMapping("/{publicId}/commissions")
    public ResponseEntity<ApiResponse<SubAgentCommissionLedgerDto>> commissions(@PathVariable UUID publicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Commission ledger fetched", rollupService.commissionLedger(publicId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubAgentResponse>>> list() {
        return ResponseEntity.ok(
                ApiResponse.success("Sub-agents fetched successfully", subAgentService.list()));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<SubAgentResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Sub-agent fetched successfully", subAgentService.get(publicId)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<SubAgentResponse>> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateSubAgentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sub-agent updated successfully",
                subAgentService.update(publicId, request)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        subAgentService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Sub-agent removed successfully"));
    }
}
