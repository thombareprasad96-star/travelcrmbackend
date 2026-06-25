package com.crm.travelcrm.permission.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.permission.dto.PermissionEntry;
import com.crm.travelcrm.permission.enums.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the authorities Spring Security enforces for a tenant user:
 *
 *   legacy role authorities (CRM_FULL / USER_*)  +  fine-grained Permission keys
 *
 * Fine-grained keys come from the user's saved permission map
 * ({@code user_permissions}); a user with no saved map yet falls back to the role's
 * default set ({@link Permission#defaultsFor}). TENANT_ADMIN always gets every tenant
 * permission — an admin must not be able to lock itself out of its own tenant.
 * SUPERADMIN is the platform owner (authority PLATFORM_ADMIN) and holds NO tenant-level
 * CRM permissions, so it is deliberately excluded from the tenant bypass below.
 *
 * The legacy role authorities are kept ON PURPOSE: existing
 * {@code @PreAuthorize("hasAuthority('CRM_FULL')")} checks keep passing while
 * controllers are migrated one module at a time to fine-grained keys. Once a module is
 * migrated, its restriction becomes active; until then this layer is purely additive
 * and changes nothing about current behaviour.
 *
 * Not {@code @Transactional}: it only issues a single explicit-tenant repository read,
 * and it runs inside {@code JwtAuthFilter} BEFORE TenantContext is populated, so it must
 * not rely on the Hibernate tenant filter.
 */
@Component
@RequiredArgsConstructor
public class EffectivePermissionResolver {

    private final PermissionService permissionService;

    public Collection<GrantedAuthority> resolve(User user) {
        Set<GrantedAuthority> authorities = new HashSet<>(user.getRole().authorities());
        for (String key : effectiveKeys(user)) {
            authorities.add(new SimpleGrantedAuthority(key));
        }
        return authorities;
    }

    private Set<String> effectiveKeys(User user) {
        Role role = user.getRole();

        // Tenant-admin bypass — full control of ITS OWN tenant, regardless of any saved
        // map. SUPERADMIN is excluded on purpose: it is the platform owner (PLATFORM_ADMIN),
        // not a tenant CRM user, so it gets no tenant-level permission keys here.
        if (role == Role.TENANT_ADMIN) {
            return Arrays.stream(Permission.values())
                    .map(Permission::name)
                    .collect(Collectors.toSet());
        }

        Map<String, PermissionEntry> saved =
                permissionService.savedMapOrNull(user.getTenantId(), user.getId());

        if (saved != null) {
            // A persisted row is the source of truth — even an EMPTY map means "no grants",
            // NOT a silent fall-back to role defaults (otherwise turning every permission off
            // would hand the role's full default set straight back). Unknown/stale keys ignored.
            return saved.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().isAccess())
                    .map(Map.Entry::getKey)
                    .filter(Permission::isValidKey)
                    .collect(Collectors.toSet());
        }

        // No saved row yet → fall back to the role's default permission set.
        return Permission.defaultsFor(role).stream()
                .map(Permission::name)
                .collect(Collectors.toSet());
    }
}