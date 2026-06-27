package com.crm.travelcrm.ai.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One persisted turn in a {@link ChatSession}. The {@code sessionId} is a logical FK (matches the
 * codebase's cross-aggregate FK convention). Ordering is by {@code createdAt} (BaseEntity audit field).
 */
@Entity
@Table(name = "ai_chat_messages", indexes = {
        @Index(name = "idx_ai_msg_session", columnList = "session_id, created_at"),
        @Index(name = "idx_ai_msg_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ChatMessage extends BaseTenantEntity {

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ChatRole role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** Set on ASSISTANT/TOOL turns when a tool was invoked, for traceability. Nullable. */
    @Column(name = "tool_name", length = 120)
    private String toolName;
}