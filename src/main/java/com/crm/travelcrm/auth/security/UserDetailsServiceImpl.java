package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /** Fallback used only when no tenantId is available (e.g. SuperAdmin flow). */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /** Primary path: loads user scoped to the tenant from the JWT claim. */
    public UserDetails loadUserByEmailAndTenantId(String email, Long tenantId)
            throws UsernameNotFoundException {
        return userRepository.findByEmailAndTenantIdAndDeletedAtIsNull(email, tenantId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found for email=" + email + " tenantId=" + tenantId));
    }
}