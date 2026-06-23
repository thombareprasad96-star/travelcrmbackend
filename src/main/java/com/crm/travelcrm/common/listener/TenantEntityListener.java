package com.crm.travelcrm.common.listener;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TenantEntityListener {

    @PrePersist
    public void prePersist(BaseTenantEntity entity) {
        Long contextTenant = TenantContext.getTenantId();
        if (entity.getTenantId() == null) {
            if (contextTenant == null) {
                throw new IllegalStateException(
                        "TenantContext is empty — cannot persist " +
                                entity.getClass().getSimpleName() +
                                " without a tenantId. Is JwtAuthFilter running?");
            }
            entity.setTenantId(contextTenant);
            log.debug("Auto-set tenantId={} on {}", contextTenant, entity.getClass().getSimpleName());
        } else if (contextTenant != null && !contextTenant.equals(entity.getTenantId())) {
            // A service set a tenantId that doesn't match the request's tenant — block it
            // (defense-in-depth, mirrors the cross-tenant guard in preUpdate).
            throw new SecurityException(
                    "Cross-tenant persist blocked: entity.tenantId=" +
                            entity.getTenantId() + " context.tenantId=" + contextTenant);
        }
    }

    @PreUpdate
    public void preUpdate(BaseTenantEntity entity) {
        Long contextTenant = TenantContext.getTenantId();
        if (contextTenant != null && !contextTenant.equals(entity.getTenantId())) {
            throw new SecurityException(
                    "Cross-tenant update blocked: entity.tenantId=" +
                            entity.getTenantId() + " context.tenantId=" + contextTenant);
        }
    }
}