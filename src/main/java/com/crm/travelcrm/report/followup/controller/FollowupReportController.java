package com.crm.travelcrm.report.followup.controller;

import com.crm.travelcrm.report.followup.dto.BulkCompleteRequest;
import com.crm.travelcrm.report.followup.dto.BulkCompleteResponseDTO;
import com.crm.travelcrm.report.followup.dto.FollowupSummaryDTO;
import com.crm.travelcrm.report.followup.dto.FollowupTaskDTO;
import com.crm.travelcrm.report.followup.dto.FollowupTasksResponseDTO;
import com.crm.travelcrm.report.followup.service.FollowupReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Lead Follow-up Report — task list, summary, single/bulk complete and CSV export.
 *
 * <p>Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 * The {@code assignedTo} filter and complete actions are keyed by <b>publicId</b> (UUID).
 */
@RestController
@RequestMapping("/api/reports/followup")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class FollowupReportController {

    private final FollowupReportService followupReportService;

    @GetMapping("/tasks")
    public ResponseEntity<FollowupTasksResponseDTO> getTasks(
            @RequestParam(required = false) String viewType,
            @RequestParam(required = false) String daysAhead,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reminderType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "1")  int page) {
        return ResponseEntity.ok(followupReportService.getTasks(
                viewType, daysAhead, assignedTo, priority, reminderType, search, perPage, page));
    }

    @GetMapping("/summary")
    public ResponseEntity<FollowupSummaryDTO> getSummary(
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reminderType) {
        return ResponseEntity.ok(followupReportService.getSummary(assignedTo, priority, reminderType));
    }

    @PatchMapping("/tasks/{publicId}/complete")
    public ResponseEntity<FollowupTaskDTO> markComplete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(followupReportService.markComplete(publicId));
    }

    @PatchMapping("/tasks/bulk-complete")
    public ResponseEntity<BulkCompleteResponseDTO> bulkComplete(@RequestBody BulkCompleteRequest request) {
        return ResponseEntity.ok(followupReportService.bulkComplete(request.getIds()));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String viewType,
            @RequestParam(required = false) String daysAhead,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String reminderType,
            @RequestParam(required = false) String search) {
        byte[] csv = followupReportService.exportCsv(viewType, daysAhead, assignedTo, priority, reminderType, search);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=followup-report.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}