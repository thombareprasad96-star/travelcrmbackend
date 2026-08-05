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

    // ── All Tasks grid columns (snapshots — see Task entity) ────────────────────────────────

    /** "For (Trip#)" — deep-link target. Null when the task has no booking link. */
    private UUID bookingPublicId;

    /** "For (Trip#)" — the human-facing booking code, e.g. {@code TRP79799}. */
    private String bookingCode;

    /** "Guest" — the booking's customer name snapshot. */
    private String customerName;

    /** "Trip Source" — {@code LeadSource.getDisplayName()} of the originating lead, e.g. "WhatsApp". */
    private String tripSource;

    /** "Created By" — display name of the creator ({@code ownerUserId}). */
    private String createdByName;

    private List<String> logs;

    /** Derived: still open and past its calendar anchor. */
    private boolean overdue;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}