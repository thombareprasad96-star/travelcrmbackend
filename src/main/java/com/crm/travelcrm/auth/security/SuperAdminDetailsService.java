package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SuperAdminDetailsService implements UserDetailsService {

    private final SuperAdminRepository superAdminRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Soft-deleted platform accounts are never found — this runs on every authenticated
        // request, so a deleted SuperAdmin's live tokens stop working immediately.
        return superAdminRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new UsernameNotFoundException("SuperAdmin not found"));
    }
}