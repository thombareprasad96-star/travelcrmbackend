package com.crm.travelcrm.reminder.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A CRM follow-up reminder (lead contact, payment, document, birthday, etc.).
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity}; soft-deleted via {@code deletedAt}.
 * Field names match the frontend {@code reminderService.js} contract exactly.
 *
 * <p>When a reminder becomes due, {@code ReminderScheduler} publishes a
 * {@code NotifyEvent} so the notification module pushes it to the owner's bell —
 * the only coupling between this module and the notification module.
 */
@Entity
@Table(name = "reminders", indexes = {
        @Index(name = "idx_reminder_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_reminder_status",   columnList = "status"),
        @Index(name = "idx_reminder_due_date", columnList = "due_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Reminder extends BaseTenantEntity {

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    @Builder.Default
    private ReminderType type = ReminderType.Custom;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private ReminderPriority priority = ReminderPriority.Medium;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private ReminderStatus status = ReminderStatus.Active;

    /** Frontend lead reference, e.g. "LD1042". Stored as a plain string (not an FK). */
    @Column(name = "lead_id", length = 50)
    private String leadId;

    @Column(name = "lead_name")
    private String leadName;

    @Column(name = "phone", length = 30)
    private String phone;

    /** Frontend user reference, e.g. "U01". Stored as a plain string (not an FK). */
    @Column(name = "assign_to", length = 50)
    private String assignTo;

    /** When the reminder is due — UTC. */
    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Internal: the tenant user (Long id) who owns this reminder — used to route the
     * SSE "reminder due" notification. Not exposed in the API response.
     */
    // No DB-level FK — cross-aggregate reference to users.id, enforced at the application layer.
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /**
     * Internal: flipped to {@code true} once the scheduler has fired the due-notification,
     * preventing repeated pushes. Not exposed in the API response.
     */
    @Column(name = "notified", nullable = false)
    @Builder.Default
    private boolean notified = false;

    /** Free-form activity logs appended via {@code POST /api/reminders/{id}/logs}. */
    @ElementCollection
    @CollectionTable(name = "reminder_logs", joinColumns = @JoinColumn(name = "reminder_id"))
    @Column(name = "log_text", columnDefinition = "TEXT")
    @Builder.Default
    private List<String> logs = new ArrayList<>();
}