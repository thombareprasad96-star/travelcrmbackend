package com.crm.travelcrm.report.geographic.controller;

import com.crm.travelcrm.report.geographic.dto.GeoDistributionResponseDTO;
import com.crm.travelcrm.report.geographic.dto.GeoSummaryDTO;
import com.crm.travelcrm.report.geographic.service.GeographicReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Geographic Distribution report — grouped lead rows, summary and CSV export.
 * Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 */
@RestController
@RequestMapping("/api/reports/geographic")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class GeographicReportController {

    private final GeographicReportService geographicReportService;

    @GetMapping("/data")
    public ResponseEntity<GeoDistributionResponseDTO> getData(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Departing Cities") String viewType,
            @RequestParam(required = false) String leadType,
            @RequestParam(required = false) String leadStage,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "1")  int page) {
        return ResponseEntity.ok(geographicReportService.getData(
                startDate, endDate, viewType, leadType, leadStage, search, perPage, page));
    }

    @GetMapping("/summary")
    public ResponseEntity<GeoSummaryDTO> getSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String leadType,
            @RequestParam(required = false) String leadStage) {
        return ResponseEntity.ok(geographicReportService.getSummary(startDate, endDate, leadType, leadStage));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Departing Cities") String viewType,
            @RequestParam(required = false) String leadType,
            @RequestParam(required = false) String leadStage,
            @RequestParam(required = false) String search) {
        byte[] csv = geographicReportService.exportCsv(startDate, endDate, viewType, leadType, leadStage, search);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=geographic-report.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}