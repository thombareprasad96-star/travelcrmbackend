package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Request-context helpers shared by the fleet service impls (same semantics as the
 * private helpers in {@code ReminderServiceImpl}, extracted because five impls need them).
 */
final class FleetContext {

    private FleetContext() {
    }

    static Long tenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — no tenant bound to this request");
        }
        return tenantId;
    }

    static User user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new IllegalStateException("Fleet module is available to tenant users only");
        }
        return user;
    }

    static String username() {
        return user().getUsername();
    }
}