package com.crm.travelcrm.report.intldomestic.controller;

import com.crm.travelcrm.report.intldomestic.dto.DestinationDTO;
import com.crm.travelcrm.report.intldomestic.dto.DistributionDTO;
import com.crm.travelcrm.report.intldomestic.dto.IntlDomesticResponseDTO;
import com.crm.travelcrm.report.intldomestic.dto.TripTypeDataDTO;
import com.crm.travelcrm.report.intldomestic.service.IntlDomesticService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * International vs Domestic report — per-panel data, combined, distribution, destinations, CSV export.
 * Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 */
@RestController
@RequestMapping("/api/reports/international-domestic")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class IntlDomesticController {

    private final IntlDomesticService intlDomesticService;

    @GetMapping("/all")
    public ResponseEntity<IntlDomesticResponseDTO> getAll(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(intlDomesticService.getAll(startDate, endDate, dateType, status));
    }

    @GetMapping("/international")
    public ResponseEntity<TripTypeDataDTO> getInternational(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(intlDomesticService.getByTripType("International", startDate, endDate, dateType, status));
    }

    @GetMapping("/domestic")
    public ResponseEntity<TripTypeDataDTO> getDomestic(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(intlDomesticService.getByTripType("Domestic", startDate, endDate, dateType, status));
    }

    @GetMapping("/distribution")
    public ResponseEntity<DistributionDTO> getDistribution(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(intlDomesticService.getDistribution(startDate, endDate, dateType, status));
    }

    @GetMapping("/destinations")
    public ResponseEntity<List<DestinationDTO>> getDestinations(
            @RequestParam String tripType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "5") int topN) {
        return ResponseEntity.ok(intlDomesticService.getTopDestinations(tripType, startDate, endDate, dateType, status, topN));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status) {
        byte[] csv = intlDomesticService.exportCsv(startDate, endDate, dateType, status);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=intl-domestic-report.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}