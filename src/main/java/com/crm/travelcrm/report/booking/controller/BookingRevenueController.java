package com.crm.travelcrm.report.booking.controller;

import com.crm.travelcrm.report.booking.dto.BookingRevenueResponseDTO;
import com.crm.travelcrm.report.booking.dto.BookingStatisticsDTO;
import com.crm.travelcrm.report.booking.dto.RevenueBreakdownDTO;
import com.crm.travelcrm.report.booking.dto.RevenueSummaryDTO;
import com.crm.travelcrm.report.booking.service.BookingRevenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Booking Revenue report — rows, summary, breakdown, statistics and CSV export.
 * Bare DTOs (no {@code ApiResponse}); gated by {@code CRM_FULL}; tenant scoping in the service.
 */
@RestController
@RequestMapping("/api/reports/revenue")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class BookingRevenueController {

    private final BookingRevenueService bookingRevenueService;

    @GetMapping("/bookings")
    public ResponseEntity<BookingRevenueResponseDTO> getBookings(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String minAmount,
            @RequestParam(required = false) String maxAmount,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "25") int perPage,
            @RequestParam(defaultValue = "1")  int page) {
        return ResponseEntity.ok(bookingRevenueService.getBookings(
                startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount, search, perPage, page));
    }

    @GetMapping("/summary")
    public ResponseEntity<RevenueSummaryDTO> getSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String minAmount,
            @RequestParam(required = false) String maxAmount) {
        return ResponseEntity.ok(bookingRevenueService.getSummary(
                startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount));
    }

    @GetMapping("/breakdown")
    public ResponseEntity<RevenueBreakdownDTO> getBreakdown(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType) {
        return ResponseEntity.ok(bookingRevenueService.getBreakdown(startDate, endDate, dateType));
    }

    @GetMapping("/statistics")
    public ResponseEntity<BookingStatisticsDTO> getStatistics(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType) {
        return ResponseEntity.ok(bookingRevenueService.getStatistics(startDate, endDate, dateType));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "Booking Date") String dateType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String minAmount,
            @RequestParam(required = false) String maxAmount,
            @RequestParam(required = false) String search) {
        byte[] csv = bookingRevenueService.exportCsv(
                startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount, search);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=booking-revenue.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}