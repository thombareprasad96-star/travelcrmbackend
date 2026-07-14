package com.crm.travelcrm.subagent.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * B2B franchise sub-agent profile — the tenant-side record paired 1:1 with a {@code SUB_AGENT}
 * User. Holds the markup the parent set (applied to this sub-agent's quotations in Phase 4),
 * per-sub-agent white-label branding, and the provisioning status. Created together with the
 * User inside {@code SubAgentServiceImpl.create}.
 *
 * <p>Tenant-scoped ({@link BaseTenantEntity}); {@code tenantId} auto-stamped on persist. Not
 * {@code Ownable} — it belongs to the tenant admin's management surface, not to the sub-agent.</p>
 */
@Entity
@Table(name = "sub_agent_profiles",
        uniqueConstraints = @UniqueConstraint(name = "uq_subagent_user", columnNames = {"user_id"}),
        indexes = {
                @Index(name = "idx_subagent_tenant", columnList = "tenant_id"),
                @Index(name = "idx_subagent_status", columnList = "tenant_id,status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SubAgentProfile extends BaseTenantEntity {

    /** The sub-agent User (users.id, role = SUB_AGENT). Logical FK, application-enforced. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // ── Markup (parent-set; applied to this sub-agent's quotations in Phase 4) ──
    @Enumerated(EnumType.STRING)
    @Column(name = "markup_type", nullable = false, length = 10)
    @Builder.Default
    private MarkupType markupType = MarkupType.PERCENT;

    /** PERCENT → a percent (0–100). FIXED → an absolute amount added to the quotation total. */
    @Column(name = "markup_value", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal markupValue = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private SubAgentStatus status = SubAgentStatus.ACTIVE;

    // ── White-label branding (used by the PDF resolver in Phase 4; all nullable) ──
    @Column(name = "brand_name", length = 150)
    private String brandName;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "brand_color", length = 20)
    private String brandColor;
}
