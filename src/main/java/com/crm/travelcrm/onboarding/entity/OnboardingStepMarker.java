package com.crm.travelcrm.onboarding.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One row per (tenant, step_key). Serves two purposes on a single table to keep the surface small:
 * <ul>
 *   <li>A per-step manual <b>dismiss</b> marker — {@code step_key} = an {@link
 *       com.crm.travelcrm.onboarding.model.OnboardingStep} name, {@code dismissed=true}.</li>
 *   <li>A once-ever <b>celebration-fired</b> sentinel — {@code step_key = "__CELEBRATION_FIRED__"};
 *       its mere existence is the exactly-once guard, so the notification is sent at most once per
 *       tenant. The sentinel matches no enum key, so it is naturally ignored when mapping markers
 *       back to steps.</li>
 * </ul>
 * Modelled on {@code UsageAlertMarker}: guard-then-save-then-publish, with the unique constraint as
 * the race backstop. {@code tenantId}/{@code publicId}/audit/soft-delete are inherited — never set
 * {@code tenantId} manually (auto-stamped from {@code TenantContext} by {@code TenantEntityListener}).
 */
@Entity
@Table(name = "onboarding_step_markers",
        uniqueConstraints = @UniqueConstraint(name = "uq_onboarding_step", columnNames = {"tenant_id", "step_key"}),
        indexes = @Index(name = "idx_onboarding_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class OnboardingStepMarker extends BaseTenantEntity {

    @Column(name = "step_key", nullable = false, length = 40)
    private String stepKey;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed;
}