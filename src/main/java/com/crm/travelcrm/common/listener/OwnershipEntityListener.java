package com.crm.travelcrm.common.listener;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.entity.Ownable;
import jakarta.persistence.PrePersist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Stamps the row-level owner ({@code owner_user_id}) on any {@link Ownable} entity at persist time,
 * from the authenticated tenant {@link User} in the SecurityContext.
 *
 * <p>This is the companion to {@link TenantEntityListener}: that one stamps {@code tenant_id} (which
 * tenant), this one stamps {@code owner_user_id} (which user within the tenant) so per-user /
 * sub-agent data scoping can filter by owner.</p>
 *
 * <p>Only stamps when the owner is not already set — a service may assign it explicitly (e.g. a
 * sub-agent's lead auto-assigned to itself) and that wins. Non-{@code User} principals (SuperAdmin,
 * portal {@code TravelerPrincipal}) and system / scheduler writes with no authentication leave it
 * {@code null}; owner-less rows fall to ALL-scope visibility and never leak into an OWN-scoped
 * user's view.</p>
 *
 * <p>Not a Spring bean — JPA instantiates entity listeners itself, so this reads the security
 * context statically, exactly as {@link TenantEntityListener} reads {@code TenantContext}.</p>
 */
@Slf4j
public class OwnershipEntityListener {

    @PrePersist
    public void prePersist(Object entity) {
        if (!(entity instanceof Ownable ownable) || ownable.getOwnerUserId() != null) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u && u.getId() > 0) {
            ownable.setOwnerUserId(u.getId());
            log.debug("Auto-set ownerUserId={} on {}", u.getId(), entity.getClass().getSimpleName());
        }
    }
}
