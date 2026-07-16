package com.crm.travelcrm.lead.assignment.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.permission.dto.PermissionEntry;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.EffectivePermissionResolver;
import com.crm.travelcrm.permission.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the pool of users a new lead may be recommended to: within the tenant, <b>active</b>
 * ({@code isActive}), <b>enabled</b>, <b>not locked</b>, <b>not deleted</b>, holding the required
 * lead permission — and never a {@code SUPERADMIN} or a {@code SUB_AGENT} (a sub-agent can only ever
 * own its own leads).
 *
 * <p>Effective-permission membership can't be expressed in SQL (per-user overrides live in an opaque
 * JSON blob), so this does it in Java but WITHOUT an N+1: two batched reads (active users of the
 * tenant + their saved permission maps in one query) followed by the exact same rule
 * {@link EffectivePermissionResolver#keysFor} applies to the current principal at login. A role-only
 * filter would be wrong — an override can grant or revoke the permission independently of the role.
 */
@Component
@RequiredArgsConstructor
public class AssignableUserResolver {

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final EffectivePermissionResolver effectivePermissionResolver;

    /**
     * Active, enabled, unlocked, non-deleted tenant users (excluding SuperAdmin and sub-agents) whose
     * effective permissions include {@code required}. Ordered by name (the underlying finder's order).
     */
    public List<User> resolve(Long tenantId, Permission required) {
        // 1) Active + non-deleted tenant users, name-ordered (the assignee-dropdown source).
        List<User> candidates = userRepository
                .findByTenantIdAndIsActiveTrueAndDeletedAtIsNullOrderByNameAsc(tenantId).stream()
                .filter(u -> u.getRole() != Role.SUPERADMIN && u.getRole() != Role.SUB_AGENT)
                .filter(User::isAccountNonLocked)   // locked == null || !locked
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 2) One query for every candidate's saved permission map (absent = never customised).
        List<Long> ids = candidates.stream().map(User::getId).toList();
        Map<Long, Map<String, PermissionEntry>> savedMaps =
                permissionService.savedMapsForUsers(tenantId, ids);

        // 3) Keep only those whose EFFECTIVE keys include the required permission (same rule as login).
        String requiredKey = required.name();
        return candidates.stream()
                .filter(u -> effectivePermissionResolver
                        .keysFor(u.getRole(), savedMaps.get(u.getId()))
                        .contains(requiredKey))
                .toList();
    }
}