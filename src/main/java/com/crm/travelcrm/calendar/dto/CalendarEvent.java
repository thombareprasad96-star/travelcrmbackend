package com.crm.travelcrm.calendar.dto;

import com.crm.travelcrm.calendar.enums.CalendarSource;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One unified, read-model calendar entry. Merges first-class {@code TASK}/{@code REMINDER} rows with
 * booking-derived events into a single shape the frontend can render on the month/week/day grid.
 *
 * <p>All times are UTC {@link Instant}s. Date-only sources (trips, service lines, payment-due) are
 * normalized to start-of-day UTC with {@code allDay = true}. {@code referencePublicId} deep-links to
 * the backing entity ({@code referenceType} = TASK / REMINDER / BOOKING). Only {@code TASK} (and
 * {@code REMINDER}) events are {@code editable}; derived events are informational.
 */
@Getter
@Builder
public class CalendarEvent {

    /** Stable composite key, e.g. {@code "TASK:<uuid>"} / {@code "PAYMENT_DUE:<bookingUuid>"}. */
    private String id;

    private CalendarSource source;
    /** Legend label for colouring/grouping (e.g. "Confirmed Trips", "Team Meeting"). */
    private String category;

    private String title;
    private String subtitle;

    private Instant start;
    private Instant end;
    private boolean allDay;

    /** Task/reminder priority name, else null. */
    private String priority;
    /** Task/reminder/booking status name, else null. */
    private String status;
    /** Outstanding amount for a PAYMENT_DUE event, else null. */
    private BigDecimal amount;

    private String referenceType;
    private UUID referencePublicId;

    private String assigneeName;

    /** True only for first-class rows the user may open/edit (tasks, reminders). */
    private boolean editable;
}
