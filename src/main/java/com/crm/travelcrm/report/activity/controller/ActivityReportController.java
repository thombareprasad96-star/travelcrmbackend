package com.crm.travelcrm.report.activity.controller;

import com.crm.travelcrm.report.activity.dto.ActivityLogDetailDTO;
import com.crm.travelcrm.report.activity.dto.ActivityLogsResponseDTO;
import com.crm.travelcrm.report.activity.dto.ActivitySummaryDTO;
import com.crm.travelcrm.report.activity.service.ActivityReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Activity Reports — audit-trail listing, summary, detail and CSV export.
 *
 * <p>Returns <b>bare DTOs</b> (no {@code ApiResponse} envelope) because the FE report services read
 * {@code res.data.X} directly. Gated by {@code CRM_FULL} (every operational tenant role; SuperAdmin
 * has no tenant and is excluded). Tenant scoping is enforced in the service via {@code TenantContext}.
 */
@RestController
@RequestMapping("/api/reports/activity")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class ActivityReportController {

    private final ActivityReportService activityReportService;

    @GetMapping("/logs")
    public ResponseEntity<ActivityLogsResponseDTO> getLogs(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "50") int perPage,
            @RequestParam(defaultValue = "1")  int page) {
        return ResponseEntity.ok(
                activityReportService.getLogs(startDate, endDate, action, userType, userId, perPage, page));
    }

    @GetMapping("/summary")
    public ResponseEntity<ActivitySummaryDTO> getSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(activityReportService.getSummary(startDate, endDate));
    }

    @GetMapping("/logs/{publicId}")
    public ResponseEntity<ActivityLogDetailDTO> getLogById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(activityReportService.getDetail(publicId));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String userId) {
        byte[] csv = activityReportService.exportCsv(startDate, endDate, action, userType, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=activity-report.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}