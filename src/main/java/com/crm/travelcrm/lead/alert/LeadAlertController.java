package com.crm.travelcrm.lead.alert;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.lead.alert.dto.LeadAlertDto;
import com.crm.travelcrm.lead.alert.dto.LeadAlertStatsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The incoming-leads screen's two reads. Live updates arrive over the existing SSE stream
 * ({@code LeadAlertEvent}); these endpoints are what the page loads with, and what it falls back to
 * when a connection drops and reconnects.
 *
 * <p>Both are gated on {@code LEAD_READ} — claiming needs {@code LEAD_CLAIM}, but SEEING the open
 * queue is the point of broadcasting it: a manager without the claim permission still needs to
 * watch the SLA countdown.
 *
 * <p><b>Plus {@code CRM_FULL}, which is what excludes a franchise sub-agent.</b> These two reads are
 * tenant-wide roll-ups of leads nobody owns yet, carrying the prospect's name, phone and budget. A
 * {@code SUB_AGENT} holds {@code LEAD_READ} but is a different commercial party inside the same
 * tenant: {@code AssignableUserResolver} refuses to let it own a lead and {@code LeadClaimService}
 * refuses to let it claim one, so handing it the feed would give it the claim widening's reach with
 * none of the widening's justification (that whoever can SEE an open lead can also TAKE it). Same
 * gate, same reason, as the other tenant-wide roll-ups — {@code BookingController},
 * {@code CustomerController}, {@code CalendarController}.
 */
@RestController
@RequestMapping("/api/leads/alerts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('LEAD_READ') and hasAuthority('CRM_FULL')")
public class LeadAlertController {

    private final LeadAlertService leadAlertService;

    /** GET /api/leads/alerts/open — every lead currently claimable, newest first. */
    @GetMapping("/open")
    public ResponseEntity<ApiResponse<List<LeadAlertDto>>> openToClaim() {
        return ResponseEntity.ok(
                ApiResponse.success("Open leads fetched", leadAlertService.openToClaim()));
    }

    /** GET /api/leads/alerts/stats — the four tiles, for today in the tenant's timezone. */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<LeadAlertStatsDto>> stats() {
        return ResponseEntity.ok(
                ApiResponse.success("Lead alert stats fetched", leadAlertService.stats()));
    }
}
