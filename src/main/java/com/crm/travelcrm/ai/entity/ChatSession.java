package com.crm.travelcrm.ai.entity;

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
 * A Disha conversation owned by one tenant user. Tenant isolation comes from {@link BaseTenantEntity}
 * (auto-stamped {@code tenant_id} + {@code tenantFilter}); ownership is enforced in the service by
 * {@code userId} so a user only ever sees their own sessions. External id is {@code publicId}.
 */
@Entity
@Table(name = "ai_chat_sessions", indexes = {
        @Index(name = "idx_ai_session_tenant_user", columnList = "tenant_id, user_id"),
        @Index(name = "idx_ai_session_public", columnList = "public_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ChatSession extends BaseTenantEntity {

    /** Owner — internal user id (never exposed; resolved from the security context). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;
}