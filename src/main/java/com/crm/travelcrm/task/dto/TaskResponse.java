package com.crm.travelcrm.task.dto;

import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Task response. Exposes the external {@code publicId} (never the internal Long id, per the
 * project default) and the fields the task board / calendar frontend reads. {@code overdue} is
 * derived server-side ({@link com.crm.travelcrm.task.entity.Task#isOverdue()}).
 */
@Getter
@Builder
public class TaskResponse {

    private UUID publicId;
    private String title;
    private String description;
    private TaskCategory category;
    private TaskPriority priority;
    private TaskStatus status;

    private UUID assignToPublicId;
    private String assignToName;

    private Instant startAt;
    private Instant endAt;
    private boolean allDay;
    private Instant dueDate;

    private String location;
    private Instant completedAt;
    private String notes;

    private UUID leadPublicId;
    private String leadName;

    private List<String> logs;

    /** Derived: still open and past its calendar anchor. */
    private boolean overdue;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}