package com.crm.travelcrm.ai.tool;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Enforces the SAME per-module authority the REST controllers require, but on the Disha AI tool path.
 *
 * <p>The chat endpoint is gated only by {@code @PreAuthorize("isAuthenticated()")}, and the
 * {@code @Tool} methods reach the domain services directly — those services enforce tenant isolation
 * (they are {@code @Transactional}, so the Hibernate {@code tenantFilter} applies) but NOT the
 * per-module authority that the equivalent REST endpoints put behind {@code @PreAuthorize}
 * ({@code BOOKING_READ}, {@code QUOTATION_READ}, {@code REMINDER_READ}, {@code CRM_FULL}). Without this
 * check a low-privilege user could ask the chatbot for data the UI would 403. The tool methods call
 * {@link #require(String)} first, so a denial is raised before any service call.</p>
 *
 * <p>The current user's authorities are read from the {@link SecurityContextHolder}: the chat worker
 * thread re-establishes the captured {@code Authentication} (see {@code ChatOrchestrationService}), so
 * the same {@code GrantedAuthorities} the REST layer checks are present here. Because the check runs
 * inside {@code AiAuditService.recordToolCall}, the resulting {@link AccessDeniedException} is recorded
 * as a DENIED tool call and then rethrown.</p>
 */
@Component
public class AiToolAuthorizer {

    /**
     * Assert the current user holds {@code authority}; otherwise throw {@link AccessDeniedException}
     * (mapped to a 403-equivalent, tenant-admins pass because the resolver grants them every key).
     */
    public void require(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
        if (!granted) {
            throw new AccessDeniedException(
                    "You don't have permission to use this (requires " + authority + ").");
        }
    }
}