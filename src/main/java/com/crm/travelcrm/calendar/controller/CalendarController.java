package com.crm.travelcrm.calendar.controller;

import com.crm.travelcrm.calendar.dto.CalendarEvent;
import com.crm.travelcrm.calendar.dto.CalendarSummaryDto;
import com.crm.travelcrm.calendar.service.CalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Unified calendar REST API — a merged event feed and a dashboard summary.
 *
 * <p>Bare-DTO responses (no {@code ApiResponse} envelope), consistent with the sibling task /
 * reminder modules the calendar aggregates. The feed is gated by {@code TASK_READ} (per-user, row-
 * scoped in the service — sub-agents get only their own tasks + reminders); the tenant-wide summary
 * requires {@code CRM_FULL}, which blocks sub-agents from the operational roll-up.
 */
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TASK_READ')")   // class default; /summary overrides below
public class CalendarController {

    private final CalendarService calendarService;

    /**
     * Merged event feed for a date window. {@code from}/{@code to} are ISO-8601 instants
     * (e.g. {@code 2025-05-01T00:00:00Z}); when omitted the service defaults to the current month.
     */
    @GetMapping
    public ResponseEntity<List<CalendarEvent>> events(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String categories,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ResponseEntity.ok(calendarService.getEvents(
                parseInstant(from), parseInstant(to), categories, assignee, mine));
    }

    /** Tenant-wide dashboard snapshot for {@code date} (ISO date, e.g. {@code 2025-05-21}; default today). */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // tenant-wide operational roll-up — blocks sub-agents
    public ResponseEntity<CalendarSummaryDto> summary(@RequestParam(required = false) String date) {
        return ResponseEntity.ok(calendarService.getSummary(parseDate(date)));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}