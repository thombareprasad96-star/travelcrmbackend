package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.CallDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A logged phone call.
 *
 * <p><b>This module never places a call.</b> Rows arrive from a PBX/provider webhook where one is
 * connected, and by manual entry otherwise. Click-to-call and the in-browser softphone (mute / hold
 * / keypad) are deliberately out of scope — those controls require a WebRTC browser SDK, which
 * server-side call bridging cannot provide.
 *
 * <p>Every call also writes a companion {@link CommMessage} with {@code channel = CALL} pointing
 * back here through {@code callId}, so a call sorts into the customer's single timeline beside the
 * WhatsApp messages and emails instead of living in a parallel history.
 *
 * <p>{@link #outcome} is a free String, not an enum, following the {@code LeadLog.activityKind}
 * precedent: it is a display/filter label over an open vocabulary that sales teams extend
 * ("Interested", "Callback requested", "Wrong number", "Asked for brochure"), and an
 * {@code @Enumerated} column would need a CHECK-constraint refresh block — and a production
 * migration — every time someone wants a new one.
 */
@Entity
@Table(name = "comm_calls", indexes = {
        @Index(name = "idx_call_tenant",       columnList = "tenant_id"),
        @Index(name = "idx_call_conversation", columnList = "conversation_id"),
        @Index(name = "idx_call_direction",    columnList = "direction"),
        @Index(name = "idx_call_started",      columnList = "started_at"),
        @Index(name = "idx_call_agent",        columnList = "agent_user_id"),
        @Index(name = "idx_call_owner",        columnList = "owner_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommCall extends BaseTenantEntity implements Ownable {

    /** {@code comm_conversations.id} — the contact's thread this call belongs to. */
    @Column(name = "conversation_id")
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private CallDirection direction;

    /** Canonical E.164 where known; whatever the PBX reported otherwise. */
    @Column(name = "from_number", length = 30)
    private String fromNumber;

    @Column(name = "to_number", length = 30)
    private String toNumber;

    /** {@code users.id} of the staff member on the call. */
    @Column(name = "agent_user_id")
    private Long agentUserId;

    @Column(name = "agent_name", length = 150)
    private String agentName;

    /** The PBX/provider's own call id — the idempotency key for webhook-sourced rows. */
    @Column(name = "provider_call_id", length = 190)
    private String providerCallId;

    @Column(name = "provider", length = 40)
    private String provider;

    // ── Timing ────────────────────────────────────────────────────────────────────────────────

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** Null for a missed call — which is precisely how a missed call is identifiable. */
    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /**
     * Talk time in seconds.
     *
     * <p>Stored rather than derived from the timestamps: a manually logged call often has a
     * remembered duration and no precise end time, and a PBX reports billable seconds that need not
     * equal {@code endedAt - answeredAt}.
     */
    @Column(name = "duration_seconds", nullable = false)
    @Builder.Default
    private int durationSeconds = 0;

    // ── Result ────────────────────────────────────────────────────────────────────────────────

    /** Open vocabulary — see the class javadoc. */
    @Column(name = "outcome", length = 40)
    private String outcome;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** When the agent said they would call back. Feeds a Reminder when the agent asks for one. */
    @Column(name = "follow_up_at")
    private Instant followUpAt;

    // ── Recording ─────────────────────────────────────────────────────────────────────────────

    /**
     * The provider's handle for the recording, never a public URL.
     *
     * <p>Playback goes through an endpoint gated on {@code COMM_RECORDING_READ} — a permission held
     * by no role default — so a recording is never reachable by pasting a link.
     */
    @Column(name = "recording_ref", length = 255)
    private String recordingRef;

    @Column(name = "recording_available", nullable = false)
    @Builder.Default
    private boolean recordingAvailable = false;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    // ── Derived ───────────────────────────────────────────────────────────────────────────────

    /** True when nobody picked up an inbound call. */
    public boolean isMissed() {
        return direction == CallDirection.MISSED;
    }

    public Duration talkTime() {
        return Duration.ofSeconds(durationSeconds);
    }
}
