package com.crm.travelcrm.reminder.dto;

import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

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
    private String leadId;
    private String leadName;
    private String phone;
    private String assignTo;
    private Instant dueDate;
    private Instant snoozedUntil;
    private String notes;
    private LocalDateTime createdAt;
}