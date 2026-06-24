package com.crm.travelcrm.bookingreminder.controller;

import com.crm.travelcrm.bookingreminder.dto.BookingReminderRequestDto;
import com.crm.travelcrm.bookingreminder.dto.BookingReminderResponseDto;
import com.crm.travelcrm.bookingreminder.dto.BookingReminderStatsDto;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderStatus;
import com.crm.travelcrm.bookingreminder.service.BookingReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Booking-reminder REST API. Like the sibling {@code reminder} module, responses are
 * raw objects (no {@code ApiResponse} envelope) so {@code bookingReminderService.js}
 * reads {@code res.data} directly. Tenant isolation is enforced in the service via
 * {@code TenantContext}; all routes fall under SecurityConfig's authenticated rule.
 */
@RestController
@RequestMapping("/api/booking-reminders")
@RequiredArgsConstructor
public class BookingReminderController {

    private final BookingReminderService service;

    @GetMapping
    public ResponseEntity<List<BookingReminderResponseDto>> getAll(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.getAll(status));
    }

    @GetMapping("/stats")
    public ResponseEntity<BookingReminderStatsDto> getStats() {
        return ResponseEntity.ok(service.getStats());
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<BookingReminderResponseDto>> getUpcoming(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(service.getUpcoming(days));
    }

    @GetMapping("/booking/{bookingCode}")
    public ResponseEntity<List<BookingReminderResponseDto>> getByBookingCode(
            @PathVariable String bookingCode) {
        return ResponseEntity.ok(service.getByBookingCode(bookingCode));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingReminderResponseDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    public ResponseEntity<BookingReminderResponseDto> create(
            @Valid @RequestBody BookingReminderRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BookingReminderResponseDto> update(
            @PathVariable Long id, @Valid @RequestBody BookingReminderRequestDto request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/sent")
    public ResponseEntity<BookingReminderResponseDto> markSent(@PathVariable Long id) {
        return ResponseEntity.ok(service.updateStatus(id, BookingReminderStatus.Sent));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<BookingReminderResponseDto> markComplete(@PathVariable Long id) {
        return ResponseEntity.ok(service.updateStatus(id, BookingReminderStatus.Completed));
    }

    @PatchMapping("/{id}/pending")
    public ResponseEntity<BookingReminderResponseDto> markPending(@PathVariable Long id) {
        return ResponseEntity.ok(service.updateStatus(id, BookingReminderStatus.Pending));
    }

    @PostMapping("/{id}/send-now")
    public ResponseEntity<BookingReminderResponseDto> sendNow(@PathVariable Long id) {
        return ResponseEntity.ok(service.sendNow(id));
    }
}