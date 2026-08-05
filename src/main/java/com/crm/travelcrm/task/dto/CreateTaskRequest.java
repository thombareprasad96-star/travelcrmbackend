package com.crm.travelcrm.task.dto;

import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Create a task / calendar event. Enums default in the service when omitted. {@code assignToPublicId}
 * / {@code leadPublicId} are resolved to internal FKs (and validated within the tenant + caller
 * scope) by the service — never trusted from the body directly.
 */
@Getter
@Setter
public class CreateTaskRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must be at most 255 characters")
    private String title;

    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;

    private TaskCategory category;
    private TaskPriority priority;
    private TaskStatus status;

    /** Assignee (a tenant user's publicId). Null ⇒ unassigned. */
    private UUID assignToPublicId;

    /** Calendar slot start (UTC). Null ⇒ board-only unless {@code dueDate} is set. */
    private Instant startAt;
    private Instant endAt;
    private Boolean allDay;

    /** Deadline (UTC). Calendar plots here when {@code startAt} is null. */
    private Instant dueDate;

    @Size(max = 255)
    private String location;

    @Size(max = 5000)
    private String notes;

    /** Optionally link the task to a lead (its publicId), validated via the lead access guard. */
    private UUID leadPublicId;

    /**
     * Optionally link the task to a booking / trip (its publicId), resolved and validated within the
     * tenant by the service. Populates the grid's "For (Trip#)" and "Guest" columns, and — when the
     * booking traces back to a lead — "Trip Source".
     */
    private UUID bookingPublicId;
}