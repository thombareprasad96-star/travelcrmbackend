package com.crm.travelcrm.report.dashboard.controller;

import com.crm.travelcrm.report.dashboard.dto.ReportSummaryDTO;
import com.crm.travelcrm.report.dashboard.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Reports dashboard root — stat-card summary and export endpoints.
 * Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/summary")
    public ResponseEntity<ReportSummaryDTO> getSummary(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(reportService.getSummary(period, from, to));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAll(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "csv") String format) {
        byte[] data = reportService.exportAll(period, from, to, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reports-" + period + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(data);
    }

    @GetMapping("/{reportType}/export")
    public ResponseEntity<byte[]> exportSingle(
            @PathVariable String reportType,
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "csv") String format) {
        byte[] data = reportService.exportSingle(reportType, period, from, to, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + reportType + "-" + period + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(data);
    }
}
