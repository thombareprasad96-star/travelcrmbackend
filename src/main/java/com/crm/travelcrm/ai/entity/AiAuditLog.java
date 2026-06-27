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
 * Immutable audit trail of every Disha interaction and tool call: who, what tool, with which params,
 * and the outcome. Tenant-scoped. Written best-effort so auditing never breaks a chat.
 */
@Entity
@Table(name = "ai_audit_logs", indexes = {
        @Index(name = "idx_ai_audit_tenant", columnList = "tenant_id, created_at"),
        @Index(name = "idx_ai_audit_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AiAuditLog extends BaseTenantEntity {

    @Column(name = "user_id")
    private Long userId;

    /** Owning session id (logical FK), nullable for non-session audits. */
    @Column(name = "session_id")
    private Long sessionId;

    /** The user prompt that drove the turn (null for pure tool-call audits). */
    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "tool_name", length = 120)
    private String toolName;

    /** JSON snapshot of the tool params (publicId/UUID only — never internal Long ids). */
    @Column(name = "tool_params", columnDefinition = "TEXT")
    private String toolParams;

    /** Short outcome/result summary or error message. */
    @Column(name = "outcome", columnDefinition = "TEXT")
    private String outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private AiCallStatus status;

    @Column(name = "latency_ms")
    private Long latencyMs;
}