package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.permission.service.EffectivePermissionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final EffectivePermissionResolver permissionResolver;

    /** Fallback used only when no tenantId is available (e.g. SuperAdmin flow). */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
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