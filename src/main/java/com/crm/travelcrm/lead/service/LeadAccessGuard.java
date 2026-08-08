package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadOriginGroups;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
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
     * Resolve a lead that the caller may see EITHER through their row-scope OR because the lead is
     * still open to claim.
     *
     * <p><b>This is a deliberate, owner-approved widening of row-scope — not a bug and not a hole to
     * be "fixed" back to {@link #requireVisible}.</b> The product rule is that a brand-new lead is
     * broadcast to the whole tenant so anyone can take it; an OWN-scoped agent who cannot see the
     * lead cannot claim it, and claim-and-override stops meaning anything. The widening is bounded
     * on three sides:
     *
     * <ul>
     *   <li><b>In time</b> — it lasts only while {@code firstContactedAt IS NULL}. The instant
     *       someone makes contact the lead locks and ordinary row-scope resumes, so a lead being
     *       actively worked is never visible outside its owner's team.</li>
     *   <li><b>In stage</b> — a lead that reached CONVERTED or LOST is not open, whatever its
     *       contact stamp says. Without this a lead binned as LOST before anyone called would stay
     *       tenant-visible forever.</li>
     *   <li><b>In origin</b> — only a machine-made lead is claimable. A lead a colleague typed into
     *       the CRM already has the owner they chose, so widening it to the tenant would expose a
     *       private enquiry and let anyone take it. See {@link LeadOriginGroups}.</li>
     *   <li><b>By tenant</b> — the fetch is still tenant-scoped. This never crosses tenants, and the
     *       caller still needs the permission the endpoint gates on.</li>
     * </ul>
     *
     * <p>Use this ONLY on the claim-window endpoints (claim, the open-alert feed). Every other read
     * must keep using {@link #requireVisible}, or the widening leaks into places the decision never
     * covered.
     *
     * @param permissionKey the permission whose row-scope applies once the lead is LOCKED
     */
    public Lead requireVisibleOrClaimable(UUID publicId, String permissionKey) {
        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + publicId));
        if (isOpenToClaim(lead)) {
            return lead;
        }
        assertVisible(lead, permissionKey);
        return lead;
    }

    /**
     * True while the lead's claim window is open: it arrived from a machine, nobody has made first
     * contact, AND it has not reached a terminal stage. The single definition of "open", so the
     * guard, the service, the alert payload and the SQL predicates cannot drift.
     *
     * <p>The origin term is what keeps a manually created lead out of the claim queue: its creator
     * (or the manager who picked an assignee) already owns it, so there is nothing to claim and
     * nothing to broadcast. Any SQL that reproduces this predicate must carry
     * {@code origin IN :inboundOrigins} too — {@code LeadRepository.findOpenToClaim} and its
     * count/SLA siblings do.
     */
    public static boolean isOpenToClaim(Lead lead) {
        return LeadOriginGroups.isInbound(lead.getOrigin())
                && lead.getFirstContactedAt() == null
                && LeadStageGroups.isActive(lead.getLeadStage());
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