package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.permission.service.ScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single entry point for reading a {@link Lead} under BOTH tenant isolation and the caller's
 * row-level data scope (own / team / all / none).
 *
 * <p>Every module that needs a lead by id — the Lead module itself and siblings such as Quotation
 * and Reminder — must resolve it through here instead of calling
 * {@code LeadRepository.findByPublicId...} directly. A direct repository read is tenant-scoped but
 * NOT scope-scoped, so it lets a user pull a lead (and snapshot its PII) outside their visibility.
 * Centralizing the check means the scope rule can never drift between modules.</p>
 *
 * <p>Reads the tenant from {@link TenantContext} and the principal from the SecurityContext, so it
 * is only valid inside an authenticated request. Throws {@link ResourceNotFoundException} (404,
 * never 403) for missing / cross-tenant / out-of-scope leads so existence is never revealed. It
 * deliberately opens no transaction of its own — it runs inside the caller's, so the returned
 * entity stays managed and mutable for update/delete flows.</p>
 */
@Component
@RequiredArgsConstructor
public class LeadAccessGuard {

    private final LeadRepository leadRepository;
    private final ScopeResolver scopeResolver;

    /**
     * Resolve a lead by publicId within the current tenant AND the caller's row-level scope.
     *
     * @param permissionKey LEAD_READ for reads, LEAD_UPDATE / LEAD_DELETE for mutations
     */
    public Lead requireVisible(UUID publicId, String permissionKey) {
        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + publicId));
        assertVisible(lead, permissionKey);
        return lead;
    }

    /**
     * Scope-check an already-fetched lead (e.g. one found by email/phone). Throws 404 when the
     * lead is outside the caller's own/team visibility.
     */
    public void assertVisible(Lead lead, String permissionKey) {
        Long ownerId = lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null;
        if (!scopeResolver.canSee(currentUser(), permissionKey, ownerId)) {
            throw new ResourceNotFoundException("Lead not found: " + lead.getPublicId());
        }
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId.");
        }
        return tenantId;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("No tenant user in security context");
    }
}