package com.crm.travelcrm.notification.domain.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.notification.domain.enums.ReminderStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Scheduled reminder — fires a {@link com.crm.travelcrm.notification.api.NotifyEvent}
 * when {@code remindAt} is reached.
 *
 * <p>Status machine:
 * <pre>
 *   PENDING ──scheduler──▶ PROCESSING ──success──▶ SENT
 *           ──snooze──▶    SNOOZED    ──time up──▶ PROCESSING ──▶ SENT
 *           ──dismiss──▶   DISMISSED
 * </pre>
 *
 * <p>PROCESSING is an internal sentinel that prevents double-firing when
 * multiple app instances run concurrently (RSK-002).
 */
@Entity
@Table(name = "reminders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Reminder extends BaseTenantEntity {

    /** Internal FK to users.id — the user who created this reminder. */
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** When to fire — always UTC, stored as TIMESTAMPTZ. */
    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReminderStatus status = ReminderStatus.PENDING;

    /** Set by snooze endpoint; poller fires when {@code now >= snoozedUntil}. */
    @Column(name = "snoozed_until")
    private Instant snoozedUntil;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_public_id")
    private UUID referencePublicId;

    public void snooze(Instant until) {
        this.status = ReminderStatus.SNOOZED;
        this.snoozedUntil = until;
    }

    public void dismiss() {
        this.status = ReminderStatus.DISMISSED;
    }

    /** True when the reminder's effective fire-time has passed. */
    public boolean isDue(Instant now) {
        if (status == ReminderStatus.PENDING) return !now.isBefore(remindAt);
        if (status == ReminderStatus.SNOOZED && snoozedUntil != null) return !now.isBefore(snoozedUntil);
        return false;
    }
}