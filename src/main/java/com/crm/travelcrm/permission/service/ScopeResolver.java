package com.crm.travelcrm.permission.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.permission.dto.PermissionEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the DATA SCOPE (own / team / all / none) for a user + permission, and turns
 * it into the set of owner user-ids whose records that user may see.
 *
 * Scope is the second dimension of permissions (the first is access on/off). It comes
 * from the user's saved permission map (the scope dropdown); a user with no saved entry
 * falls back to a sensible role default. TENANT_ADMIN always sees everything in its tenant.
 *
 * Services call {@link #visibleUserIds(User, String)} and filter their list/board queries
 * by the returned owner ids. This is the row-level half of permission enforcement (the
 * method-level @PreAuthorize handles access; this handles visibility).
 */
@Component
@RequiredArgsConstructor
public class ScopeResolver {

    private final PermissionService permissionService;
    private final UserRepository userRepository;

    public enum Scope { OWN, TEAM, ALL, NONE }

    /**
     * The owner user-ids whose records {@code user} may see for {@code permissionKey}.
     * <ul>
     *   <li>{@code null}  → no restriction (ALL) — the caller should skip the owner filter.</li>
     *   <li>empty set     → NONE — the caller should return nothing.</li>
     *   <li>non-empty set → OWN/TEAM — the caller filters {@code owner_id IN (set)}.</li>
     * </ul>
     */
    public Set<Long> visibleUserIds(User user, String permissionKey) {
        return switch (resolveScope(user, permissionKey)) {
            case ALL  -> null;
            case NONE -> Set.of();
            case OWN  -> Set.of(user.getId());
            case TEAM -> {
                Set<Long> ids = new HashSet<>();
                ids.add(user.getId());
                ids.addAll(userRepository.findIdsByTenantIdAndManagerId(
                        user.getTenantId(), user.getId()));
                yield ids;
            }
        };
    }

    /**
     * Row-level guard for single-record reads and mutations: {@code true} if {@code user} may act
     * on a record owned by {@code ownerId} under {@code permissionKey}'s data scope.
     * <ul>
     *   <li>ALL  → always true (no owner restriction)</li>
     *   <li>OWN/TEAM → true only when {@code ownerId} is in the visible owner set</li>
     *   <li>NONE → always false</li>
     * </ul>
     * Callers should translate {@code false} into a 404 (not 403) so the existence of a record
     * outside the user's scope is never revealed. The method-level {@code @PreAuthorize} handles
     * the access dimension; this handles the visibility dimension.
     */
    public boolean canSee(User user, String permissionKey, Long ownerId) {
        Set<Long> visible = visibleUserIds(user, permissionKey);
        return visible == null || (ownerId != null && visible.contains(ownerId));
    }

    public Scope resolveScope(User user, String permissionKey) {
        // Org admin sees everything within its own tenant.
        if (user.getRole() == Role.TENANT_ADMIN) return Scope.ALL;

        // Explicit per-user scope from the saved permission map wins.
        Map<String, PermissionEntry> saved = permissionService.rawForUserId(user.getTenantId(), user.getId());
        if (saved != null) {
            PermissionEntry e = saved.get(permissionKey);
            if (e != null && e.getScope() != null && !e.getScope().isBlank()) {
                return parse(e.getScope());
            }
        }

        // Role default scope when the user has not been customized.
        return switch (user.getRole()) {
            case MANAGER    -> Scope.TEAM;   // their team's records (User.managerId)
            case ACCOUNTANT -> Scope.ALL;    // finance works across the tenant
            default         -> Scope.OWN;    // TRAVEL_AGENT, STAFF → their own records
        };
    }

    private Scope parse(String s) {
        return switch (s.trim().toLowerCase()) {
            case "all"  -> Scope.ALL;
            case "team" -> Scope.TEAM;
            case "none" -> Scope.NONE;
            default     -> Scope.OWN;
        };
    }
}
