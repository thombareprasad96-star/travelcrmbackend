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

    /**
     * Whether the caller may see fleet money.
     *
     * <p>Needed because two endpoints are gated on plain {@code FLEET_READ} — they are operational,
     * and a dispatcher must reach them — yet the response would otherwise carry cost figures. The
     * duty slip is the case in point: printing it is the dispatcher's job, but the office-use cost
     * block on it is not for his eyes. The gate therefore has to live INSIDE the response, not only
     * on the route.
     */
    static boolean canSeeMoney() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "FLEET_MONEY_READ".equals(a.getAuthority()));
    }
}