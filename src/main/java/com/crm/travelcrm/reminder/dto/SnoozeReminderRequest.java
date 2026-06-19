package com.crm.travelcrm.reminder.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * PATCH /api/reminders/{id}/snooze body: {@code { "snoozedUntil": "2026-06-21T10:00:00.000Z" }}.
 */
@Getter
@Setter
public class SnoozeReminderRequest {

    @NotNull
    private Instant snoozedUntil;
}