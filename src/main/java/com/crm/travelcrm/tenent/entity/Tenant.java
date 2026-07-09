package com.crm.travelcrm.tenent.entity;

import com.crm.travelcrm.common.entity.BaseEntity;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tenants", indexes = {
        @Index(name = "idx_tenant_email",             columnList = "email"),
        @Index(name = "idx_tenant_organization_code", columnList = "organization_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant extends BaseEntity {

    @Column(name = "organization_name", nullable = false, length = 150)
    private String organizationName;

    // Human-readable unique code used in subdomains: acme.travelcrm.com
    @Column(name = "organization_code", nullable = false, unique = true, length = 50)
    private String organizationCode;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "address", length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 20)
    @Builder.Default
    private TenantPlan plan = TenantPlan.STARTER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "subscription_start_date")
    private LocalDate subscriptionStartDate;

    @Column(name = "subscription_end_date")
    private LocalDate subscriptionEndDate;

    // Max users allowed under this tenant's plan
    @Column(name = "max_users", nullable = false)
    @Builder.Default
    private Integer maxUsers = 5;

    // Max leads allowed under this tenant's plan; null = unlimited. Denormalized from Plan on
    // create / plan-change (nullable so ddl-auto can add it to the existing tenants table).
    @Column(name = "max_leads")
    private Integer maxLeads;

    // Effective module access — seeded from the plan, editable by the SuperAdmin (Feature Flags).
    // Empty ⇒ the entitlement service falls back to the plan's modules (covers pre-existing tenants).
    // LAZY: only the entitlement endpoints touch it, so tenant list / analytics never load it.
    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "tenant_modules", joinColumns = @JoinColumn(name = "tenant_id"))
    @Column(name = "module", length = 40)
    private Set<String> enabledModules = new HashSet<>();

    /**
     * Operational = usable by its staff: not soft-deleted, and {@code ACTIVE} or {@code TRIAL}.
     * The single source of truth for "can this tenant log in / create users / operate" — used by
     * the login gate and user provisioning so a SuperAdmin suspend/expire/soft-delete takes effect.
     */
    public boolean isOperational() {
        return !isDeleted()
                && (status == TenantStatus.ACTIVE || status == TenantStatus.TRIAL);
    }
}