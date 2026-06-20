package com.crm.travelcrm.reminder.dto;

import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reminder response. Exposes the numeric {@code id} (per confirmed ID decision) and
 * only the fields the frontend reads in {@code Reminders.jsx} / {@code reminderService.js}.
 */
@Getter
@Builder
public class ReminderResponseDto {

    private Long id;
    private String title;
    private String description;
    private ReminderType type;
    private ReminderPriority priority;
    private ReminderStatus status;

    // Proper references (use these on the frontend going forward)
    private UUID leadPublicId;
    private UUID assignToPublicId;
    private String assignToName;

    /** Legacy display code, e.g. "LD1042" — mapped from the deprecated {@code leadId} column. */
    private String leadDisplayCode;

    /** @deprecated legacy string fields, kept for backward compatibility. */
    @Deprecated
    private String leadId;
    private String leadName;
    private String phone;
    @Deprecated
    private String assignTo;
    private Instant dueDate;
    private Instant snoozedUntil;
    private String notes;
    private LocalDateTime createdAt;
}