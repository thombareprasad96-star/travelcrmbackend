package com.crm.travelcrm.report.traveldate.controller;

import com.crm.travelcrm.report.traveldate.dto.DurationRangeDTO;
import com.crm.travelcrm.report.traveldate.dto.PeakDateDTO;
import com.crm.travelcrm.report.traveldate.dto.PeriodAnalysisResponseDTO;
import com.crm.travelcrm.report.traveldate.dto.TravelSummaryDTO;
import com.crm.travelcrm.report.traveldate.dto.TrendDataDTO;
import com.crm.travelcrm.report.traveldate.service.TravelDateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Travel-date Analysis report — summary, trends, peak dates, period analysis, duration, CSV export.
 * Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 */
@RestController
@RequestMapping("/api/reports/travel-dates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class TravelDateController {

    private final TravelDateService travelDateService;

    @GetMapping("/summary")
    public ResponseEntity<TravelSummaryDTO> getSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(travelDateService.getSummary(startDate, endDate, bookingType, status));
    }

    @GetMapping("/trends")
    public ResponseEntity<List<TrendDataDTO>> getTrends(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Monthly") String analysisType,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(travelDateService.getTrends(startDate, endDate, analysisType, bookingType, status));
    }

    @GetMapping("/peak-dates")
    public ResponseEntity<List<PeakDateDTO>> getPeakDates(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "5") int topN) {
        return ResponseEntity.ok(travelDateService.getPeakDates(startDate, endDate, bookingType, status, topN));
    }

    @GetMapping("/analysis")
    public ResponseEntity<PeriodAnalysisResponseDTO> getAnalysis(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Monthly") String analysisType,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "1")  int page) {
        return ResponseEntity.ok(travelDateService.getAnalysis(
                startDate, endDate, analysisType, bookingType, status, perPage, page));
    }

    @GetMapping("/duration")
    public ResponseEntity<List<DurationRangeDTO>> getDuration(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(travelDateService.getDuration(startDate, endDate, bookingType, status));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Monthly") String analysisType,
            @RequestParam(required = false) String bookingType,
            @RequestParam(required = false) String status) {
        byte[] csv = travelDateService.exportCsv(startDate, endDate, analysisType, bookingType, status);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=travel-date-analysis.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}