package com.crm.travelcrm.lead.claim.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.lead.claim.dto.ClaimLeadRequestDto;
import com.crm.travelcrm.lead.claim.dto.LeadAssignmentEventDto;
import com.crm.travelcrm.lead.claim.dto.LeadClaimNoteDto;
import com.crm.travelcrm.lead.claim.dto.LeadClaimResultDto;
import com.crm.travelcrm.lead.claim.dto.ReassignLeadRequestDto;
import com.crm.travelcrm.lead.claim.service.LeadClaimService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The claim window's HTTP surface. Shares the {@code /api/leads} base with {@code LeadController};
 * kept a separate class because the claim lifecycle has its own permissions and its own service, and
 * bolting five more endpoints onto a controller whose class default is {@code LEAD_READ} is how a
 * mutating endpoint ends up silently read-gated.
 *
 * <p><b>Every method declares its own {@code @PreAuthorize}.</b> Method-level annotations OVERRIDE
 * the class default rather than stacking (the warning {@code LeadController} carries), so the class
 * default here is the most restrictive of the set and each method states its real gate explicitly.
 * A new method added without one inherits {@code LEAD_REASSIGN_LOCKED} — it fails CLOSED.
 */
@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('LEAD_REASSIGN_LOCKED')")
public class LeadClaimController {

    private final LeadClaimService leadClaimService;

    /**
     * POST /api/leads/{publicId}/claim — take an open lead, overriding its current owner.
     *
     * <p>Returns 409 with {@code details.claimLost} when someone got there first; the body names the
     * current owner and carries a fresh {@code claimVersion} to retry with.
     */
    @PostMapping("/{publicId}/claim")
    @PreAuthorize("hasAuthority('LEAD_CLAIM')")
    public ResponseEntity<ApiResponse<LeadClaimResultDto>> claim(
            @PathVariable UUID publicId,
            @Valid @RequestBody ClaimLeadRequestDto request) {

        LeadClaimResultDto result = leadClaimService.claim(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Lead claimed", result));
    }

    /**
     * POST /api/leads/{publicId}/contacted — first contact made. Closes the claim window, freezes
     * the SLA and moves the lead to Contacted. The owner is NOT changed.
     */
    @PostMapping("/{publicId}/contacted")
    @PreAuthorize("hasAuthority('LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<LeadClaimResultDto>> markContacted(
            @PathVariable UUID publicId,
            @RequestBody(required = false) LeadClaimNoteDto body) {

        LeadClaimResultDto result =
                leadClaimService.markContacted(publicId, body == null ? null : body.getNote());
        return ResponseEntity.ok(ApiResponse.success("Lead marked as contacted", result));
    }

    /** POST /api/leads/{publicId}/reassign — move a LOCKED lead to another owner. */
    @PostMapping("/{publicId}/reassign")
    @PreAuthorize("hasAuthority('LEAD_REASSIGN_LOCKED')")
    public ResponseEntity<ApiResponse<LeadClaimResultDto>> reassign(
            @PathVariable UUID publicId,
            @Valid @RequestBody ReassignLeadRequestDto request) {

        LeadClaimResultDto result = leadClaimService.reassign(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Lead reassigned", result));
    }

    /**
     * POST /api/leads/{publicId}/reopen-claim — undo an accidental "Mark Contacted", putting the
     * lead back into the claim pool. Only from Contacted; the measured first-response time survives.
     */
    @PostMapping("/{publicId}/reopen-claim")
    @PreAuthorize("hasAuthority('LEAD_REASSIGN_LOCKED')")
    public ResponseEntity<ApiResponse<LeadClaimResultDto>> reopenClaim(
            @PathVariable UUID publicId,
            @RequestBody(required = false) LeadClaimNoteDto body) {

        LeadClaimResultDto result =
                leadClaimService.reopenClaimWindow(publicId, body == null ? null : body.getNote());
        return ResponseEntity.ok(ApiResponse.success("Claim window reopened", result));
    }

    /** GET /api/leads/{publicId}/assignment-history — the ownership timeline, oldest first. */
    @GetMapping("/{publicId}/assignment-history")
    @PreAuthorize("hasAuthority('LEAD_READ')")
    public ResponseEntity<ApiResponse<List<LeadAssignmentEventDto>>> history(
            @PathVariable UUID publicId) {

        return ResponseEntity.ok(ApiResponse.success(
                "Assignment history fetched", leadClaimService.history(publicId)));
    }
}
