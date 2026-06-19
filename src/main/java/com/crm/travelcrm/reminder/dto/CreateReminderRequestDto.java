package com.crm.travelcrm.reminder.dto;

import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * POST /api/reminders body. Field names mirror the frontend
 * {@code CreateReminder.jsx} payload exactly.
 */
@Getter
@Setter
public class CreateReminderRequestDto {

    @NotBlank
    @Size(max = 255)
    private String title;

    private String description;

    /** First_contact, Follow_up, Quotation, Payment, Document, Birthday, Confirmation, Custom. */
    private ReminderType type;

    /** High, Medium, Low. */
    private ReminderPriority priority;

    /** Active, Snoozed, Completed, Dismissed. */
    private ReminderStatus status;

    private String leadId;
    private String leadName;
    private String phone;
    private String assignTo;

    /** UTC ISO-8601. */
    @NotNull
    private Instant dueDate;

    private Instant snoozedUntil;

    private String notes;
}