package com.crm.travelcrm.task.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A team task / calendar event: an assignable to-do (task board) that can also be scheduled onto
 * the unified calendar (a meeting, a scheduled follow-up, a deadline).
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity} and soft-deleted via {@code deletedAt}. Implements
 * {@link Ownable} so the B2B sub-agent row-scope ({@code SubAgentScope}) confines a sub-agent to
 * the tasks it owns; {@code ownerUserId} = creator (set in {@code TaskServiceImpl.create}).
 *
 * <p><b>Calendar placement</b> = {@code startAt} if set, else {@code dueDate} (see
 * {@link #calendarInstant()}). A task with neither is board-only (My Tasks / Kanban, never on the
 * calendar grid). All datetimes are UTC {@link Instant}s.
 *
 * <p>Cross-aggregate references ({@code assignToUserId}→users.id, {@code leadRefId}→leads.id) are
 * logical FKs with NO DB constraint — validated + snapshotted at the application layer, the same
 * pattern as {@code Reminder} and {@code Booking}.
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_task_status",   columnList = "status"),
        @Index(name = "idx_task_due_date", columnList = "due_date"),
        @Index(name = "idx_task_start_at", columnList = "start_at"),
        @Index(name = "idx_task_assignee", columnList = "assign_to_user_id"),
        @Index(name = "idx_task_owner",    columnList = "owner_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Task extends BaseTenantEntity implements Ownable {

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    @Builder.Default
    private TaskCategory category = TaskCategory.GENERAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private TaskStatus status = TaskStatus.TODO;

    // ── Assignee snapshot (logical FK to users.id; tenant-scoped, app-enforced) ──────────────
    @Column(name = "assign_to_user_id")
    private Long assignToUserId;

    /** Denormalized snapshot of the assignee's publicId — lets the FE pre-select on edit. */
    @Column(name = "assign_to_public_id")
    private UUID assignToPublicId;

    /** Denormalized snapshot of the assignee's display name — avoids N+1 in list/board/calendar. */
    @Column(name = "assign_to_name", length = 150)
    private String assignToName;

    /**
     * Row-level owner = creator. Second isolation dimension after {@code tenant_id}
     * (see {@link Ownable}); set explicitly in the service so owner == creator is guaranteed.
     * No DB-level FK — logical reference to users.id.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** When the task/event is scheduled on the calendar (UTC). Null = board-only (no calendar slot). */
    @Column(name = "start_at")
    private Instant startAt;

    /** Optional end of a timed event (UTC). */
    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "all_day", nullable = false)
    @Builder.Default
    private boolean allDay = false;

    /** Deadline for board / overdue semantics (UTC). Calendar falls back to this when startAt is null. */
    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "location", length = 255)
    private String location;

    /** Stamped when the task moves to {@code DONE}; cleared if it re-opens. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Optional lead link (Lead is NOT Ownable → validated via LeadAccessGuard) ─────────────
    @Column(name = "lead_id_ref")
    private Long leadRefId;

    @Column(name = "lead_public_id")
    private UUID leadPublicId;

    @Column(name = "lead_name", length = 200)
    private String leadName;

    /** Free-form activity log, appended via {@code POST /api/tasks/{id}/logs}. */
    @ElementCollection
    @CollectionTable(name = "task_logs", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "log_text", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> logs = new ArrayList<>();

    // ── Derived (not persisted) ──────────────────────────────────────────────────────────────

    /** Calendar anchor: the explicit schedule if present, else the deadline; null ⇒ board-only. */
    @Transient
    public Instant calendarInstant() {
        return startAt != null ? startAt : dueDate;
    }

    /** True when still open (TODO/IN_PROGRESS) and its anchor moment is in the past. */
    @Transient
    public boolean isOverdue() {
        if (status == TaskStatus.DONE || status == TaskStatus.CANCELLED) return false;
        Instant anchor = calendarInstant();
        return anchor != null && anchor.isBefore(Instant.now());
    }
}