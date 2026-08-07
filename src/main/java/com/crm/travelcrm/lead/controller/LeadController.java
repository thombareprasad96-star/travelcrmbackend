package com.crm.travelcrm.lead.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.lead.dto.AddLeadLogRequestDto;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadBoardColumnDto;
import com.crm.travelcrm.lead.dto.LeadLogResponseDto;
import com.crm.travelcrm.lead.dto.LeadLogStatsDto;
import com.crm.travelcrm.lead.dto.LeadLogSummaryResponseDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.LeadStatsSummaryDto;
import com.crm.travelcrm.lead.dto.UpdateLeadStageRequestDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.service.LeadLogService;
import com.crm.travelcrm.lead.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('LEAD_READ')")   // class default; mutating methods override below
// NOTE (rollout reference): method-level @PreAuthorize OVERRIDES this class default, it does not
// stack. So every mutating endpoint MUST declare its own LEAD_CREATE/UPDATE/DELETE — a new method
// added without one silently inherits read-only LEAD_READ (fails OPEN to read, not closed).
public class LeadController {

    private final LeadService leadService;
    private final LeadLogService leadLogService;

    @PostMapping
    @PreAuthorize("hasAuthority('LEAD_CREATE')")
    public ResponseEntity<ApiResponse<LeadResponseDto>> createLead(
            @Valid @RequestBody CreateLeadRequestDto request) {

        // No customer email/phone in the log line. The prod Log4j2 config routes INFO to a rolling
        // FILE that is retained and backed up, so anything written here becomes a second, unguarded
        // copy of the customer contact data the CRM is supposed to hold in one place. The service
        // logs the lead CODE once the row exists — that identifies the record without carrying PII.
        log.info("Create lead request received");
        LeadResponseDto response = leadService.createLead(request);
        log.info("Lead {} created", response.getLeadCode());
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

    /**
     * The leads list. Every filter is optional and applied IN THE DATABASE — previously this method
     * declared only the four paging params, so the {@code search}/{@code stage}/{@code leadType}/
     * date arguments AllLeads.jsx has always sent were silently dropped by Spring and the UI showed
     * an unfiltered page while looking filtered.
     *
     * <p>{@code stage} and {@code leadType} are Strings, not enums, on purpose: the wire format is
     * the enum's {@code displayName} ("New Lead"), and Spring's default enum binding matches the
     * CONSTANT name ({@code NEW_LEAD}) and would 400 on every real request. The service resolves
     * them through {@code fromValue}, which accepts either spelling.
     *
     * <p>{@code activeOnly} and {@code followUpDueBy} are the two WORK-QUEUE filters and are not
     * stages — "Active" is the complement of the terminal stages, "Follow-ups" is a date predicate.
     * They exist so the list can express exactly what the Active and Follow-ups dashboard cards
     * count; the client used to send {@code stage=Active}, which {@code LeadStage.fromValue}
     * rejects. {@code size} is clamped and {@code sortBy} whitelisted in the service
     * ({@code PageSupport}), so neither can reach Hibernate unvalidated.
     */
    @GetMapping
    public ResponseEntity<PagedApiResponse<LeadResponseDto>> getAllLeads(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "10")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir,
            @RequestParam(required = false)           String search,
            @RequestParam(required = false)           String stage,
            @RequestParam(required = false)           String leadType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false)           Boolean activeOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate followUpDueBy) {

        Page<LeadResponseDto> leadPage = leadService.getAllLeads(
                page, size, sortBy, sortDir, search, stage, leadType, fromDate, toDate,
                activeOnly, followUpDueBy);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Leads fetched successfully",
                        leadPage.getContent(),
                        PaginationMeta.from(leadPage, sortBy, sortDir)));
    }

    // ── Kanban board ────────────────────────────────────────────────────────────

    /** All leads grouped into the seven pipeline columns — powers LeadKanban.jsx. */
    @GetMapping("/board")
    public ResponseEntity<ApiResponse<List<LeadBoardColumnDto>>> getLeadBoard() {
        List<LeadBoardColumnDto> board = leadService.getLeadBoard();
        return ResponseEntity.ok(ApiResponse.success("Lead board fetched successfully", board));
    }

    /** Drag-and-drop: move a lead to a new stage without sending the full lead payload. */
    @PatchMapping("/{publicId}/stage")
    @PreAuthorize("hasAuthority('LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<LeadResponseDto>> updateLeadStage(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateLeadStageRequestDto request) {

        log.info("Received stage-change request for publicId: {} -> {}",
                publicId, request.getLeadStage());
        LeadResponseDto response = leadService.updateLeadStage(publicId, request.getLeadStage());
        return ResponseEntity.ok(ApiResponse.success("Lead stage updated successfully", response));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<LeadResponseDto>> updateLead(
            @PathVariable UUID publicId,
            @Valid @RequestBody CreateLeadRequestDto request) {

        log.info("Received update lead request for publicId: {}", publicId);
        LeadResponseDto response = leadService.updateLead(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Lead updated successfully", response));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('LEAD_DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteLead(@PathVariable UUID publicId) {

        log.info("Received delete lead request for publicId: {}", publicId);
        leadService.deleteLead(publicId);
        return ResponseEntity.ok(ApiResponse.success("Lead deleted successfully"));
    }

    // ── Activity logs ───────────────────────────────────────────────────────────

    /** Add an activity log to a lead (optionally creating a follow-up reminder). */
    @PostMapping("/{publicId}/logs")
    @PreAuthorize("hasAuthority('LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<LeadLogResponseDto>> addLeadLog(
            @PathVariable UUID publicId,
            @Valid @RequestBody AddLeadLogRequestDto request) {

        log.info("Adding activity log to lead publicId: {}", publicId);
        LeadLogResponseDto response = leadLogService.addLog(publicId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Log added successfully", response, 201));
    }

    /** All activity logs for one lead, newest first — powers the per-lead LeadLogs page. */
    @GetMapping("/{publicId}/logs")
    public ResponseEntity<ApiResponse<List<LeadLogResponseDto>>> getLeadLogs(
            @PathVariable UUID publicId) {

        return ResponseEntity.ok(
                ApiResponse.success("Lead logs fetched", leadLogService.getLogsForLead(publicId)));
    }

    /** Leads (visible to the caller) that have at least one log — the All-Lead-Logs grid. */
    @GetMapping("/logs/summary")
    public ResponseEntity<ApiResponse<LeadLogSummaryResponseDto>> getLeadLogSummary(
            @RequestParam(required = false)                  String search,
            @RequestParam(required = false)                  String stage,
            @RequestParam(name = "userId", required = false) UUID userPublicId,
            @RequestParam(defaultValue = "1")                int page,
            @RequestParam(defaultValue = "12")               int perPage) {

        LeadLogSummaryResponseDto summary =
                leadLogService.getLogSummary(search, stage, userPublicId, page, perPage);
        return ResponseEntity.ok(ApiResponse.success("Lead log summary fetched", summary));
    }

    /** Roll-up totals for the All-Lead-Logs hero stat cards. */
    @GetMapping("/logs/stats")
    public ResponseEntity<ApiResponse<LeadLogStatsDto>> getLeadLogStats() {
        return ResponseEntity.ok(
                ApiResponse.success("Lead log stats fetched", leadLogService.getLogStats()));
    }

    /** Soft-delete a single activity log from a lead. */
    @DeleteMapping("/{publicId}/logs/{logId}")
    @PreAuthorize("hasAuthority('LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> deleteLeadLog(
            @PathVariable UUID publicId,
            @PathVariable UUID logId) {

        log.info("Deleting activity log {} from lead {}", logId, publicId);
        leadLogService.deleteLog(publicId, logId);
        return ResponseEntity.ok(ApiResponse.success("Log deleted successfully"));
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

    /**
     * Roll-up behind the All-Leads dashboard cards — pipeline shape, money in play, today's
     * follow-ups and the period conversion cohort, all aggregated in the database over the caller's
     * own LEAD_READ scope.
     *
     * <p>It exists because the cards used to be computed client-side from
     * {@code GET /leads?page=0&size=100}: past a hundred leads every figure described the newest
     * page while reading like a tenant total. Anything whose scope is wider than the page the table
     * is holding belongs here, not in the browser.
     *
     * <p>{@code from}/{@code to} are inclusive ISO dates; omitting both gives the tenant's current
     * calendar month in the TENANT's timezone. Class-level {@code LEAD_READ} governs — the row
     * scope inside decides how much of the tenant each caller actually sees.
     */
    @GetMapping("/stats/summary")
    public ResponseEntity<ApiResponse<LeadStatsSummaryDto>> getStatsSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(
                ApiResponse.success("Lead stats summary fetched",
                        leadService.getStatsSummary(from, to)));
    }
}
