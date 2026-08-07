package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.MessageDirection;
import com.crm.travelcrm.communication.enums.MessageStatus;
import com.crm.travelcrm.communication.enums.MessageVisibility;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row on the unified timeline: a WhatsApp message, an email, an SMS, a call entry or a note.
 *
 * <h2>Why one table for every channel</h2>
 * The product exists to show a single chronological thread. A {@code UNION ALL} across five
 * per-channel tables cannot be indexed for "newest 30 rows of this conversation" and degrades with
 * every channel added. Channel-specific fields therefore live beside this row, not in it:
 * {@code CommMessageEmail} carries the RFC headers, {@code CommCall} carries call detail (referenced
 * by {@link #callId}), and {@code CommAttachment} carries bytes. This row stays narrow and hot.
 *
 * <h2>Notes are messages</h2>
 * A note is {@code channel = INTERNAL_NOTE}, {@code direction = INTERNAL}, with
 * {@link #visibility} deciding who may read it. That buys full-text search, attachments, mentions
 * and correct timeline ordering with no second subsystem — and it is why {@link #visibility} is
 * applied as a repository predicate rather than filtered in a mapper.
 *
 * <h2>This table will be the largest in the application</h2>
 * <ul>
 *   <li>Never add a {@code bytea} column here — attachments live in their own table so a list query
 *       cannot drag blobs (the {@code TravelerDocument} lesson).</li>
 *   <li>{@code search_tsv} is a Postgres GENERATED column with a GIN index, declared in V2 PART 17
 *       and deliberately NOT mapped here. Hibernate {@code validate} does not object to columns the
 *       entity does not know about, and mapping a {@code tsvector} through JPA buys nothing —
 *       search runs as a native query that passes {@code tenant_id} explicitly.</li>
 *   <li>{@link #occurredAt} is the timeline clock and is a {@code LocalDateTime}, matching
 *       {@code BaseEntity.createdAt} and the existing {@code WaMessageLog.sentAt} /
 *       {@code EmailMessageLog.sentAt}. Future-scheduled instants elsewhere in this module use
 *       {@code Instant}, matching {@code Task.dueDate} — observed-past and scheduled-future are
 *       different clocks and the split is intentional.</li>
 * </ul>
 */
@Entity
@Table(name = "comm_messages", indexes = {
        @Index(name = "idx_msg_tenant",       columnList = "tenant_id"),
        @Index(name = "idx_msg_conversation", columnList = "conversation_id"),
        @Index(name = "idx_msg_occurred",     columnList = "occurred_at"),
        @Index(name = "idx_msg_channel",      columnList = "channel"),
        @Index(name = "idx_msg_status",       columnList = "status"),
        @Index(name = "idx_msg_owner",        columnList = "owner_user_id"),
        @Index(name = "idx_msg_sender",       columnList = "sender_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommMessage extends BaseTenantEntity implements Ownable {

    /** {@code comm_conversations.id}. Every message belongs to exactly one thread. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /**
     * Mirrors the conversation's channel at write time.
     *
     * <p>Denormalised on purpose: the channel-specific tabs (WhatsApp / Email / SMS / Calls) filter
     * messages directly, and a thread's channel is immutable anyway, so the copy cannot drift.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    @Builder.Default
    private MessageVisibility visibility = MessageVisibility.PUBLIC;

    /** Plain text. The full-text search source, and what a WhatsApp/SMS body actually is. */
    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    /** Rich body — email only. Never populated for WhatsApp/SMS. */
    @Column(name = "body_html", columnDefinition = "TEXT")
    private String bodyHtml;

    // ── Delivery ──────────────────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private MessageStatus status = MessageStatus.QUEUED;

    @Column(name = "status_at")
    private LocalDateTime statusAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * The provider's own id — the idempotency key for inbound deliveries.
     *
     * <p>Unique per {@code (tenant_id, provider_message_id)} where non-null, so a webhook retry
     * (Interakt's response SLA is ~3 s, and a slow reply means a retry) appends nothing. Two tenants
     * may legitimately see the same provider id, hence the tenant in the key.
     */
    @Column(name = "provider_message_id", length = 190)
    private String providerMessageId;

    // ── Authorship ────────────────────────────────────────────────────────────────────────────

    /** {@code users.id} of the staff member who sent it. Null for every inbound message. */
    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Column(name = "sender_name", length = 150)
    private String senderName;

    /**
     * Row-level owner, used by {@code SubAgentScope}.
     *
     * <p>Set explicitly by the service. An inbound message is written on a webhook thread with no
     * authenticated principal, so {@code OwnershipEntityListener} stamps nothing and the row would
     * otherwise be invisible to a sub-agent forever. Inbound messages inherit the conversation's
     * owner.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    // ── Links ─────────────────────────────────────────────────────────────────────────────────

    /** {@code comm_templates.id} when this was sent from a template. */
    @Column(name = "template_id")
    private Long templateId;

    /** {@code comm_messages.id} this is a reply to — threads an email chain. */
    @Column(name = "reply_to_message_id")
    private Long replyToMessageId;

    /** {@code comm_calls.id}. Set exactly when {@link #channel} is {@code CALL}. */
    @Column(name = "call_id")
    private Long callId;

    @Column(name = "attachment_count", nullable = false)
    @Builder.Default
    private int attachmentCount = 0;

    /** Denormalised for the timeline header when a note or message was pinned to a lead. */
    @Column(name = "lead_public_id")
    private UUID leadPublicId;

    // ── Clock ─────────────────────────────────────────────────────────────────────────────────

    /**
     * When the message actually happened — provider timestamp for inbound, send time for outbound.
     *
     * <p>Distinct from {@code createdAt}, which is when WE stored it. A webhook replayed after an
     * outage would otherwise reorder a customer's thread.
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    // ── Derived ───────────────────────────────────────────────────────────────────────────────

    /** True for the two note visibilities — never transmitted, never shown to the contact. */
    public boolean isNote() {
        return channel == CommChannel.INTERNAL_NOTE;
    }

    /** Short single-line form for the conversation list row. */
    public String preview(int max) {
        if (bodyText == null || bodyText.isBlank()) {
            return attachmentCount > 0 ? "[attachment]" : "";
        }
        String flat = bodyText.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
