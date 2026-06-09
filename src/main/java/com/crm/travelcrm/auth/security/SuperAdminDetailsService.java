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
        return superAdminRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("SuperAdmin not found"));
    }
}