package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.communication.enums.ConversationMemberRole;
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
 * A staff member's membership of an internal conversation — plus their own read state.
 *
 * <p>Only {@code kind = INTERNAL} conversations have members. A group chat genuinely has N
 * different unread counts, so read state lives here as {@link #lastReadAt} rather than as a single
 * counter on the conversation. Customer threads take the opposite route (a denormalised
 * {@code unreadCount}) because they have exactly one agent who owes the reply — see
 * {@link CommConversation}.
 *
 * <p>{@link #pinnedAt} is per-member on purpose: pinning is a personal shortcut, not a property of
 * the channel, so one person pinning "Operations" must not pin it for everyone.
 *
 * <p>Unique on {@code (tenant_id, conversation_id, user_id) WHERE deleted_at IS NULL} — the
 * {@code deleted_at} arm lets someone removed from a channel be re-added later.
 */
@Entity
@Table(name = "comm_conversation_members", indexes = {
        @Index(name = "idx_ccm_tenant",       columnList = "tenant_id"),
        @Index(name = "idx_ccm_conversation", columnList = "conversation_id"),
        @Index(name = "idx_ccm_user",         columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommConversationMember extends BaseTenantEntity {

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** {@code users.id}. Logical FK, tenant-scoped and validated in the service. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_public_id")
    private UUID userPublicId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 10)
    @Builder.Default
    private ConversationMemberRole memberRole = ConversationMemberRole.MEMBER;

    /**
     * Everything up to this instant has been read by this member.
     *
     * <p>A timestamp rather than a message id: it survives a message being soft-deleted, and it
     * answers "how many unread?" with one indexed COUNT instead of a positional walk.
     */
    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    /** Personal pin. Null = not pinned. */
    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    /** Suppresses notifications for this member only; the messages still arrive. */
    @Column(name = "muted", nullable = false)
    @Builder.Default
    private boolean muted = false;

    public boolean isPinned() {
        return pinnedAt != null;
    }
}
