package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One user's toggle for one notification type on one channel — the Communication Settings screen.
 *
 * <h2>Why this is a new table and not an extension of {@code notificationsetting/}</h2>
 * {@code NotificationSetting} is not a preference model despite the package name. It is a single
 * JSON blob per tenant of lead-stage auto-reminder rules, keyed {@code UNIQUE(tenant_id)} alone, and
 * <b>nothing in the codebase reads it</b>. It cannot express "this user, this event, this channel"
 * without changing its key, and extending it would silently inherit a model with zero consumers.
 *
 * <p>More importantly: there is no opt-out anywhere in the application today. Channel selection is
 * decided by the publisher when it builds a {@code NotifyEvent}, and no consumer consults a
 * preference. A settings screen that writes toggles nothing enforces is worse than no settings
 * screen — so this table is only meaningful once the check runs inside a delivery channel bean at
 * send time.
 *
 * <p><b>Absence means enabled.</b> A missing row is "on", so a new user and a newly added event type
 * both behave sensibly without a backfill, and only an explicit opt-out is stored.
 */
@Entity
@Table(name = "comm_notification_prefs", indexes = {
        @Index(name = "idx_cnp_tenant", columnList = "tenant_id"),
        @Index(name = "idx_cnp_user",   columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommNotificationPref extends BaseTenantEntity {

    /** {@code users.id}. Preferences are per user, never per tenant. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * What happened: {@code NEW_MESSAGE}, {@code MENTION}, {@code MISSED_CALL},
     * {@code TASK_REMINDER}, {@code FOLLOW_UP_REMINDER}, …
     *
     * <p>A free String, matching the free-form {@code NotifyEvent.type} vocabulary it gates. An enum
     * here would have to be refreshed in a CHECK constraint every time any module invents an event.
     */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** Delivery route: {@code IN_APP}, {@code EMAIL}, {@code BROWSER}. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
