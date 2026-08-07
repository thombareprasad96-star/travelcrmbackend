package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One {@code @mention} of a colleague inside a note or an internal message.
 *
 * <p>A row rather than a parsed-on-read scan of the body: the Mentions tab is "everything addressed
 * to me across every conversation", which as a text search over the largest table in the
 * application would be unusable, and an unread mention needs a per-user read state that a body
 * cannot carry.
 *
 * <p>Unique on {@code (tenant_id, message_id, mentioned_user_id) WHERE deleted_at IS NULL} so
 * mentioning someone twice in one note notifies them once.
 */
@Entity
@Table(name = "comm_mentions", indexes = {
        @Index(name = "idx_cmn_tenant",  columnList = "tenant_id"),
        @Index(name = "idx_cmn_message", columnList = "message_id"),
        @Index(name = "idx_cmn_user",    columnList = "mentioned_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommMention extends BaseTenantEntity {

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** Denormalised so the Mentions feed can jump straight to the thread without a join. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** {@code users.id} of the person mentioned. */
    @Column(name = "mentioned_user_id", nullable = false)
    private Long mentionedUserId;

    /** {@code users.id} of the author — excluded from notification (the actor rule). */
    @Column(name = "mentioned_by_user_id")
    private Long mentionedByUserId;

    /** Null until the mentioned user opens it. The unread predicate for the Mentions badge. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    public boolean isUnread() {
        return readAt == null;
    }
}
