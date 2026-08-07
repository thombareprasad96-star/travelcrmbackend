package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ScheduledMessageStatus;
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

import java.time.Instant;

/**
 * A one-off message queued to go out later — "Schedule SMS" and its WhatsApp/email equivalents.
 *
 * <p><b>Scope note.</b> This is deliberately NOT an automation engine. The repo already has two
 * (marketing campaigns/drips, and birthday/anniversary triggers), and a third would mean two systems
 * disagreeing about what was sent to whom. Recurring and triggered sends stay in {@code marketing/};
 * this table holds only "send this exact message to this exact contact at this exact time".
 *
 * <p>{@link #sendAt} is an {@link Instant}, matching {@code Task.dueDate} and
 * {@code Reminder.dueDate} — the scheduler compares against {@code Instant.now()}. Observed-past
 * timestamps elsewhere in this module use {@code LocalDateTime}; the split between scheduled-future
 * and observed-past is intentional and follows what each neighbouring subsystem already does.
 */
@Entity
@Table(name = "comm_scheduled_messages", indexes = {
        @Index(name = "idx_csm_tenant",       columnList = "tenant_id"),
        @Index(name = "idx_csm_send_at",      columnList = "send_at"),
        @Index(name = "idx_csm_status",       columnList = "status"),
        @Index(name = "idx_csm_conversation", columnList = "conversation_id"),
        @Index(name = "idx_csm_owner",        columnList = "owner_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommScheduledMessage extends BaseTenantEntity implements Ownable {

    /** Existing thread to append to. Null when scheduling to a contact with no thread yet. */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** {@code comm_contact_identities.id} — the destination. Required. */
    @Column(name = "contact_identity_id", nullable = false)
    private Long contactIdentityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommChannel channel;

    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "subject", length = 255)
    private String subject;

    /**
     * The resolved body, frozen at schedule time.
     *
     * <p>Deliberately not re-rendered at send time: the agent approved these exact words, and a
     * merge tag that resolves differently next Tuesday (a renamed customer, a changed tier) would
     * send something nobody reviewed.
     */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "send_at", nullable = false)
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private ScheduledMessageStatus status = ScheduledMessageStatus.SCHEDULED;

    /** Bumped by the scheduler before each attempt, so a poison row cannot loop forever. */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private short attempts = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Set once the send succeeded — links to the {@code comm_messages} row it produced. */
    @Column(name = "sent_message_id")
    private Long sentMessageId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** True when the scheduler should pick this up now. */
    public boolean isDue(Instant now) {
        return status == ScheduledMessageStatus.SCHEDULED && !sendAt.isAfter(now);
    }
}
