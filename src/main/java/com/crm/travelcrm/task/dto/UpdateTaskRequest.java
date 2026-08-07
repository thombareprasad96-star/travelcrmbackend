package com.crm.travelcrm.task.dto;

import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Partial update — every field is optional. Only non-null fields overwrite the entity
 * (MapStruct {@code NullValuePropertyMappingStrategy.IGNORE}). Supplying {@code assignToPublicId}
 * / {@code leadPublicId} re-resolves that reference; omitting it leaves the existing one untouched
 * (there is no "clear reference" here in v1).
 */
@Getter
@Setter
public class UpdateTaskRequest {

    @Size(max = 255, message = "Title must be at most 255 characters")
    private String title;

    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;

    private TaskCategory category;
    private TaskPriority priority;
    private TaskStatus status;

    private UUID assignToPublicId;

    private Instant startAt;
    private Instant endAt;
    private Boolean allDay;
    private Instant dueDate;

    @Size(max = 255)
    private String location;

    @Size(max = 5000)
    private String notes;

    private UUID leadPublicId;

    /** Re-links the task to a booking / trip. Omitting it leaves the existing link untouched. */
    private UUID bookingPublicId;

    /**
     * Explicitly REMOVE the booking / trip link (and the display snapshots that came with it).
     *
     * <p>Needed because {@code null} cannot mean "clear" on a partial update — a body that simply
     * omits {@code bookingPublicId} must leave the existing link alone, which is what every other
     * reference on this DTO does. Without a separate signal the UI's "remove trip" control would
     * silently no-op and the user would only find out after a reload.
     *
     * <p>Applied BEFORE {@code bookingPublicId}, so sending both re-links to the new trip rather
     * than leaving the outcome dependent on field order.
     */
    private Boolean clearBookingLink;
}