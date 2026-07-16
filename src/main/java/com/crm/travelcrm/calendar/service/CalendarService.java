package com.crm.travelcrm.calendar.service;

import com.crm.travelcrm.calendar.dto.CalendarEvent;
import com.crm.travelcrm.calendar.dto.CalendarSummaryDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Unified calendar: a merged event feed and a dashboard summary over the CRM's dated data. */
public interface CalendarService {

    /**
     * Merged, read-only event feed for [from, to). Includes the caller's tasks + reminders (row-scoped
     * for sub-agents) and, for non-sub-agents with booking read access, booking-derived trip / payment-
     * due / flight / hotel / visa events. {@code categories} (comma-separated {@code CalendarSource}
     * names) filters sources; {@code mine}/{@code assignee} narrow to one person's tasks & reminders.
     */
    List<CalendarEvent> getEvents(Instant from, Instant to, String categories, UUID assignee, boolean mine);

    /** Tenant-wide dashboard snapshot (KPI cards + right-rail lists) for {@code date} (default today). */
    CalendarSummaryDto getSummary(LocalDate date);
}