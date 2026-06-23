package com.crm.travelcrm.auth.api;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Single implementation of the auth module's public API surface
 * ({@link CurrentUserProvider}, {@link UserDirectory}).
 *
 * <p>This is the only class outside auth.security that reads {@code SecurityContextHolder}
 * or touches {@code UserRepository} on behalf of other modules. It centralizes the
 * "who is acting / who is this user" rules so consumers depend on the interfaces, not
 * on the {@code User} entity.
 */
@Service
@RequiredArgsConstructor
public class AuthApiService implements CurrentUserProvider, UserDirectory {

    private final UserRepository userRepository;

    // ── CurrentUserProvider ───────────────────────────────────────────────────

    @Override
    public Long currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    @Override
    public String currentUsernameOrSystem() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    // ── UserDirectory ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<String> emailById(Long userId) {
        // Tenant-scope the lookup when a tenant is bound to the thread; fall back to the
        // unscoped find for tenant-less flows (e.g. async delivery with no TenantContext)
        // so notification email resolution is never silently broken.
        Long tenantId = TenantContext.getTenantId();
        Optional<User> user = (tenantId != null)
                ? userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                : userRepository.findById(userId);
        return user.map(User::getEmail);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> activeAdminIds(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        return userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN"))
                .stream()
                .map(User::getId)
                .toList();
    }
}