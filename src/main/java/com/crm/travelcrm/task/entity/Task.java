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
        @Index(name = "idx_task_owner",    columnList = "owner_user_id"),
        @Index(name = "idx_task_booking",  columnList = "booking_id_ref"),
        // The All Tasks grid always filters (tenant, assignee, due window) together —
        // idx_task_assignee alone is not selective enough for the Today/Overdue tabs.
        @Index(name = "idx_task_tenant_assignee_due",
               columnList = "tenant_id, assign_to_user_id, due_date")
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

    // ── Optional booking ("trip") link — backs the All Tasks grid ────────────────────────────
    // Same logical-FK pattern as the lead link above: no DB constraint, resolved and validated in
    // TaskServiceImpl against the current tenant, with display values snapshotted so a 50-row grid
    // costs one query rather than fifty.

    /** Logical FK to {@code bookings.id}. No DB-level FK — validated at the application layer. */
    @Column(name = "booking_id_ref")
    private Long bookingRefId;

    /** Denormalized snapshot of the booking's publicId — lets the FE deep-link and pre-select on edit. */
    @Column(name = "booking_public_id")
    private UUID bookingPublicId;

    /**
     * Denormalized snapshot of {@code bookings.booking_code} — the human-facing "Trip#" column.
     * Snapshotted rather than joined so the grid stays readable after a booking is soft-deleted.
     */
    @Column(name = "booking_code", length = 20)
    private String bookingCode;

    /**
     * Denormalized guest name, taken from {@code Booking.customerNameSnapshot} (itself already a
     * snapshot). Backs the "Guest" column. Null for tasks with no booking link.
     */
    @Column(name = "customer_name_snapshot", length = 255)
    private String customerNameSnapshot;

    /**
     * Denormalized {@code LeadSource.getDisplayName()} of the originating lead — the "Trip Source"
     * column. A STRING, not the enum: this is a point-in-time record of where the work came from,
     * and it must survive both the lead being deleted and a future rename of the enum constant.
     * Resolved from the linked booking's lead when present, else from a directly linked lead.
     */
    @Column(name = "trip_source", length = 50)
    private String tripSource;

    /**
     * Denormalized display name of {@code ownerUserId} (the creator) — the "Created By" column.
     * Mirrors the {@code assignToName} idiom; {@code BaseEntity.createdBy} is only a login-username
     * String and is not joinable to a user.
     */
    @Column(name = "owner_name", length = 150)
    private String ownerName;

    /**
     * When the overdue alert for this task was last dispatched ({@code TaskOverdueScanner}).
     * Null ⇒ never alerted. A TIMESTAMP rather than a boolean so a future escalation policy
     * ("still overdue 24h later") can be added without another migration; the scanner clears it
     * whenever the task leaves the overdue state, so a re-opened or re-scheduled task alerts again.
     */
    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

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