package com.crm.travelcrm.notification.infrastructure;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Resolves the set of {@code TENANT_ADMIN} user ids for a tenant — the default fan-out
 * audience for tenant-wide events (lead/booking/customer/vendor lifecycle).
 *
 * <p>Used internally by {@code NotifyEventListener} / the in-app channel when a publisher
 * does not supply explicit recipients. The acting user is always excluded so no one is
 * notified about their own action.
 */
@Component
@RequiredArgsConstructor
public class TenantAdminResolver {

    private final UserRepository userRepository;

    /** Active TENANT_ADMIN ids for the tenant, excluding {@code excludeActorId} (may be null). */
    public List<Long> resolveAdmins(Long tenantId, Long excludeActorId) {
        if (tenantId == null) {
            return Collections.emptyList();
        }
        return userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN"))
                .stream()
                .map(User::getId)
                .filter(id -> excludeActorId == null || !id.equals(excludeActorId))
                .toList();
    }
}