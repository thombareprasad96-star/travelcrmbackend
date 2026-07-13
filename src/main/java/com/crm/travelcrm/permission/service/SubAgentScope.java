package com.crm.travelcrm.permission.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Row-level data scope for the B2B "restrict sub-agents only" policy.
 *
 * <p>The B2B rollout deliberately does NOT change what existing roles see: only
 * {@link Role#SUB_AGENT} is confined to the rows it owns ({@code owner_user_id = self}); every other
 * role stays tenant-wide, exactly as before. This is intentionally narrower than
 * {@link ScopeResolver} (which encodes the eventual OWN/TEAM/ALL model for ALL roles) — the
 * sub-agent-facing services call THIS so the behaviour change is isolated to the new role. When a
 * module is later migrated to per-user scope for every role, switch it to {@link ScopeResolver}.</p>
 *
 * <ul>
 *   <li>{@link #ownerFilter()} → {@code null} for every non-sub-agent (the caller skips the owner
 *       predicate); the sub-agent's own user-id otherwise (the caller filters {@code owner_user_id}).</li>
 *   <li>{@link #assertVisible} → 404 (never 403, so existence is never revealed) when a sub-agent
 *       touches a row it does not own; a no-op for every other role.</li>
 * </ul>
 */
@Component
public class SubAgentScope {

    /** True when the current authenticated principal is a sub-agent. */
    public boolean active() {
        return ownerFilter() != null;
    }

    /**
     * The owner user-id a sub-agent is confined to, or {@code null} for every other role (meaning:
     * apply no owner restriction — behaviour is unchanged for existing roles).
     */
    public Long ownerFilter() {
        User u = currentUserOrNull();
        return (u != null && u.getRole() == Role.SUB_AGENT) ? u.getId() : null;
    }

    /**
     * Guard a single fetched {@link Ownable} record: throws {@link ResourceNotFoundException} (404)
     * when the caller is a sub-agent and does not own the row. A no-op for every other role.
     *
     * @param idForMessage the id echoed in the 404 message (use the publicId, never the internal id)
     */
    public void assertVisible(Ownable entity, Object idForMessage) {
        Long restrictTo = ownerFilter();
        if (restrictTo != null
                && (entity.getOwnerUserId() == null || !restrictTo.equals(entity.getOwnerUserId()))) {
            throw new ResourceNotFoundException("Not found: " + idForMessage);
        }
    }

    private User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
    }
}
