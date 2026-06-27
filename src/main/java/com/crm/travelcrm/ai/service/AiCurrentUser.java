package com.crm.travelcrm.ai.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single place Disha reads the caller identity + tenant — reusing the exact same SecurityContext /
 * {@link TenantContext} mechanism every existing service uses. The LLM never supplies identity; it is
 * always taken from here, which is what makes tenant isolation and role scoping automatic for tools.
 */
@Component
public class AiCurrentUser {

    public User requireUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("No tenant user in security context");
    }

    public Long requireUserId() {
        return requireUser().getId();
    }

    public Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter ran and the JWT carries a tenantId.");
        }
        return tenantId;
    }

    /** Captured on the request thread so the async chat worker can re-establish the same context. */
    public Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}