package com.crm.travelcrm.reminder.controller;

import com.crm.travelcrm.reminder.dto.AddLogRequest;
import com.crm.travelcrm.reminder.dto.CreateReminderRequestDto;
import com.crm.travelcrm.reminder.dto.ReminderResponseDto;
import com.crm.travelcrm.reminder.dto.ReminderStatsDto;
import com.crm.travelcrm.reminder.dto.SnoozeReminderRequest;
import com.crm.travelcrm.reminder.dto.UpdateReminderRequestDto;
import com.crm.travelcrm.reminder.service.ReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Reminder REST API.
 *
 * <p>Responses are intentionally <b>not</b> wrapped in {@code ApiResponse}: the live
 * frontend ({@code reminderService.js}) reads {@code res.data} directly as the object/array.
 * URLs, HTTP methods and field names mirror that service exactly. {@code complete}/{@code dismiss}
 * are exposed on both {@code PATCH} (what the frontend calls today) and {@code PUT} (alias).
 *
 * <p>All routes are covered by {@code SecurityConfig}'s {@code anyRequest().authenticated()} rule —
 * no SecurityConfig change is required.
 */
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('REMINDER_READ')")   // class default; mutating methods override below
public class ReminderController {

    private final ReminderService reminderService;

    @PostMapping
    @PreAuthorize("hasAuthority('REMINDER_CREATE')")
    public ResponseEntity<ReminderResponseDto> create(
            @Valid @RequestBody CreateReminderRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reminderService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<ReminderResponseDto>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String type) {
        return ResponseEntity.ok(reminderService.getAll(status, priority, type));
    }

    @GetMapping("/stats")
    public ResponseEntity<ReminderStatsDto> getStats() {
        return ResponseEntity.ok(reminderService.getStats());
    }

    @GetMapping("/overdue")
    public ResponseEntity<List<ReminderResponseDto>> getOverdue() {
        return ResponseEntity.ok(reminderService.getOverdue());
    }

    @GetMapping("/due-today")
    public ResponseEntity<List<ReminderResponseDto>> getDueToday() {
        return ResponseEntity.ok(reminderService.getDueToday());
    }

    @GetMapping("/lead/{leadName}")
    public ResponseEntity<List<ReminderResponseDto>> getByLeadName(@PathVariable String leadName) {
        return ResponseEntity.ok(reminderService.getByLeadName(leadName));
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv() {
        byte[] csv = reminderService.exportCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reminders.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReminderResponseDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(reminderService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<ReminderResponseDto> update(
            @PathVariable Long id, @Valid @RequestBody UpdateReminderRequestDto request) {
        return ResponseEntity.ok(reminderService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('REMINDER_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        reminderService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @RequestMapping(value = "/{id}/complete", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<ReminderResponseDto> markComplete(@PathVariable Long id) {
        return ResponseEntity.ok(reminderService.markComplete(id));
    }

    @RequestMapping(value = "/{id}/dismiss", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<ReminderResponseDto> dismiss(@PathVariable Long id) {
        return ResponseEntity.ok(reminderService.dismiss(id));
    }

    @PatchMapping("/{id}/snooze")
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<ReminderResponseDto> snooze(
            @PathVariable Long id, @Valid @RequestBody SnoozeReminderRequest request) {
        return ResponseEntity.ok(reminderService.snooze(id, request.getSnoozedUntil()));
    }

    @PostMapping("/{id}/logs")
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<ReminderResponseDto> addLog(
            @PathVariable Long id, @Valid @RequestBody AddLogRequest request) {
        return ResponseEntity.ok(reminderService.addLog(id, request.getLog()));
    }

    @PatchMapping("/complete-all-overdue")
    @PreAuthorize("hasAuthority('REMINDER_UPDATE')")
    public ResponseEntity<Integer> completeAllOverdue() {
        return ResponseEntity.ok(reminderService.completeAllOverdue());
    }
}