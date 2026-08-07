package com.crm.travelcrm.communication.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.communication.entity.CommConversation;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.repository.CommConversationMemberRepository;
import com.crm.travelcrm.communication.repository.CommConversationRepository;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.permission.service.SubAgentScope;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * The single entry point for reading a conversation under tenant isolation AND the caller's
 * row-level scope. Modelled directly on {@code LeadAccessGuard} — every module that needs a
 * conversation by id resolves it here rather than calling the repository, so the scope rule cannot
 * drift between call sites.
 *
 * <p>Always throws 404, never 403, for a missing / cross-tenant / out-of-scope thread. A 403 would
 * confirm that a conversation with that id exists, which for an inbox means confirming that a
 * particular customer is talking to this agency.
 *
 * <h2>Three different scopes apply, and they are not interchangeable</h2>
 * <ol>
 *   <li><b>Tenant</b> — the {@code …AndTenantId…} finder.</li>
 *   <li><b>{@code SubAgentScope}</b> — a B2B partner sees only threads it OWNS. Narrow, and only
 *       ever active for {@code SUB_AGENT}.</li>
 *   <li><b>{@code ScopeResolver}</b> on {@code assignedUserId} — the OWN/TEAM/ALL axis that
 *       implements "assigned vs all conversations" for every role. This is the one the product
 *       requirement is actually about.</li>
 * </ol>
 * An unassigned thread ({@code assignedUserId == null}) is visible to anyone whose scope is not
 * NONE: a message from a new number belongs to nobody yet, and hiding it until someone claims it
 * means nobody ever does.
 *
 * <p>Internal conversations bypass the assignment axis entirely and are gated on MEMBERSHIP — being
 * a manager does not put you in someone else's private chat.
 */
@Component
@RequiredArgsConstructor
public class CommAccessGuard {

    private final CommConversationRepository conversationRepository;
    private final CommConversationMemberRepository memberRepository;
    private final ScopeResolver scopeResolver;
    private final SubAgentScope subAgentScope;

    /** Resolve a conversation by publicId within the tenant and the caller's scope, or 404. */
    public CommConversation requireVisible(UUID publicId, String permissionKey) {
        CommConversation conversation = conversationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + publicId));
        assertVisible(conversation, permissionKey);
        return conversation;
    }

    /** Scope-check an already-fetched conversation. */
    public void assertVisible(CommConversation conversation, String permissionKey) {
        // Sub-agent restriction first: it is the narrowest and never widens anything.
        subAgentScope.assertVisible(conversation, conversation.getPublicId());

        if (conversation.getKind() == ConversationKind.INTERNAL) {
            requireMembership(conversation);
            return;
        }

        Long assignee = conversation.getAssignedUserId();
        // An unclaimed thread is visible to everyone who can see the inbox at all — see the class
        // javadoc. canSee() would reject a null owner outright, which is the wrong answer here.
        if (assignee == null) {
            if (scopeResolver.resolveScope(currentUser(), permissionKey) == ScopeResolver.Scope.NONE) {
                throw new ResourceNotFoundException("Conversation not found: " + conversation.getPublicId());
            }
            return;
        }
        if (!scopeResolver.canSee(currentUser(), permissionKey, assignee)) {
            throw new ResourceNotFoundException("Conversation not found: " + conversation.getPublicId());
        }
    }

    /**
     * The assignee ids the caller may see, or {@code null} for no restriction (scope ALL).
     *
     * <p>An empty set means NONE — the caller must return an empty page, not an unfiltered one.
     */
    public Set<Long> visibleAssigneeIds(String permissionKey) {
        return scopeResolver.visibleUserIds(currentUser(), permissionKey);
    }

    /** True when the caller may read PRIVATE notes written by other people. */
    public boolean canReadPrivateNotes() {
        return hasAuthority(Permission.COMM_NOTE_PRIVATE_READ.key());
    }

    public Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId.");
        }
        return tenantId;
    }

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("No tenant user in security context");
    }

    public Long currentUserId() {
        return currentUser().getId();
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private void requireMembership(CommConversation conversation) {
        boolean member = memberRepository.existsByTenantIdAndConversationIdAndUserIdAndDeletedAtIsNull(
                conversation.getTenantId(), conversation.getId(), currentUserId());
        if (!member) {
            throw new ResourceNotFoundException("Conversation not found: " + conversation.getPublicId());
        }
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
