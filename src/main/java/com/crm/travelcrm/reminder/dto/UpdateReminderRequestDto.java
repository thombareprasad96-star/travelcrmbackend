package com.crm.travelcrm.reminder.dto;

import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * PUT /api/reminders/{id} body. Only non-null fields are applied (partial-friendly),
 * matching the frontend edit modal in {@code Reminders.jsx}.
 */
@Getter
@Setter
public class UpdateReminderRequestDto {

    @Size(max = 255)
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
}