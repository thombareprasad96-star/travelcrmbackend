package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.permission.service.EffectivePermissionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final EffectivePermissionResolver permissionResolver;

    /** Fallback used only when no tenantId is available (e.g. SuperAdmin flow). */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Email is unique only PER tenant (uq_user_email_tenant). With no tenant discriminator at
        // login, a shared email matches multiple tenants — fail safe (deterministic reject) instead
        // of letting the Optional finder throw NonUniqueResultException (a 500). The full fix
        // (organization/subdomain at login) is a separate, product-gated task. See H2.
        List<User> matches = userRepository.findAllByEmailAndDeletedAtIsNull(email);
        if (matches.isEmpty()) {
            throw new UsernameNotFoundException("User not found");
        }
        if (matches.size() > 1) {
            throw new UsernameNotFoundException(
                    "This email is registered under multiple organizations; sign in via your organization workspace.");
        }
        User user = matches.get(0);
        user.setEffectiveAuthorities(permissionResolver.resolve(user));
        return user;
    }

    /** Primary path: loads user scoped to the tenant from the JWT claim. */
    public UserDetails loadUserByEmailAndTenantId(String email, Long tenantId)
            throws UsernameNotFoundException {
        User user = userRepository.findByEmailAndTenantIdAndDeletedAtIsNull(email, tenantId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found for email=" + email + " tenantId=" + tenantId));
        user.setEffectiveAuthorities(permissionResolver.resolve(user));
        return user;
    }
}