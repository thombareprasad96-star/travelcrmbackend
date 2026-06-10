package com.crm.travelcrm.tenent.listener;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.BaseTenantEntity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TenantEntityListener {

    @PrePersist
    public void prePersist(BaseTenantEntity entity) {
        System.out.println(">>> TenantEntityListener.prePersist CALLED for: "
                + entity.getClass().getSimpleName()
                + " | TenantContext: " + TenantContext.getTenantId()); //
        if (entity.getTenantId() == null) {
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                throw new IllegalStateException(
                        "TenantContext is empty — cannot persist " +
                                entity.getClass().getSimpleName() +
                                " without a tenantId. Is JwtAuthFilter running?"
                );
            }
            entity.setTenantId(tenantId);
            log.debug("Auto-set tenantId={} on {}",
                    tenantId, entity.getClass().getSimpleName());
        }
    }

    @PreUpdate
    public void preUpdate(BaseTenantEntity entity) {
        Long contextTenant = TenantContext.getTenantId();
        if (contextTenant != null && !contextTenant.equals(entity.getTenantId())) {
            throw new SecurityException(
                    "Cross-tenant update blocked: entity.tenantId=" +
                            entity.getTenantId() + " context.tenantId=" + contextTenant
            );
        }
    }
}