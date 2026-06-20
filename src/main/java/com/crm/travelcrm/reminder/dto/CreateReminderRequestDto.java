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
import java.util.UUID;

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

    /** Preferred: UUID of the referenced lead. Resolved to the internal Long FK server-side. */
    private UUID leadPublicId;

    /** Preferred: UUID of the assigned user. Resolved to the internal Long FK server-side. */
    private UUID assignToPublicId;

    /** @deprecated legacy display code, e.g. "LD1042". Use {@link #leadPublicId}. */
    @Deprecated
    private String leadId;
    private String leadName;
    private String phone;
    /** @deprecated legacy user code, e.g. "U01". Use {@link #assignToPublicId}. */
    @Deprecated
    private String assignTo;

    /** UTC ISO-8601. */
    @NotNull
    private Instant dueDate;

    private Instant snoozedUntil;

    private String notes;
}