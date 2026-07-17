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
        // Email is unique platform-wide (uq_users_email_active), so this resolves to at most one
        // user and needs no tenant discriminator. H2 is closed: the ambiguity it tracked was removed
        // at the schema rather than papered over at login.
        //
        // The >1 branch is kept as defence in depth, not as expected behaviour — it is now
        // unreachable unless the constraint is missing. That is a real possibility worth failing
        // closed on: db/indexes.sql runs with continue-on-error=true, so a failed index creation
        // leaves no constraint and logs nothing at startup. Reject rather than authenticate an
        // arbitrary match, and treat a hit here as a broken constraint, not a user error.
        List<User> matches = userRepository.findAllByEmailAndDeletedAtIsNull(email);
        if (matches.isEmpty()) {
            throw new UsernameNotFoundException("User not found");
        }
        if (matches.size() > 1) {
            throw new UsernameNotFoundException("Account is ambiguous; contact support.");
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