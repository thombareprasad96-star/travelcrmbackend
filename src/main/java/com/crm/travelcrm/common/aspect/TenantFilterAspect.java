package com.crm.travelcrm.common.aspect;

import com.crm.travelcrm.common.context.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// @Order(1) is INNER relative to the TX advisor (@Order(0) in JpaConfig).
// Execution: TX proxy opens session → this @Before fires with session bound → method runs with filter active.
@Aspect
@Component
@Order(1)
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void enableTenantFilter() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }

        Session session = entityManager.unwrap(Session.class);
        if (session.getEnabledFilter("tenantFilter") == null) {
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        }
    }
}