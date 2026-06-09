// tenant/service/impl/TenantServiceImpl.java
package com.crm.travelcrm.tenent.service;


import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.exception.DuplicateTenantException;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.crm.travelcrm.tenent.mapper.TenantMapper;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder  passwordEncoder;

    @Override
    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request) {

        log.info("Creating tenant: {}", request.getOrganizationCode());

        if (tenantRepository.existsByOrganizationCode(request.getOrganizationCode())) {
            throw new DuplicateTenantException(
                    "Organization code already exists: " + request.getOrganizationCode());
        }
        if (tenantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateTenantException(
                    "Email already registered: " + request.getEmail());
        }

        // 1. Save tenant
        Tenant tenant = Tenant.builder()
                .organizationName(request.getOrganizationName())
                .organizationCode(request.getOrganizationCode().toUpperCase())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .subscriptionStartDate(request.getSubscriptionStartDate())
                .subscriptionEndDate(request.getSubscriptionEndDate())
                .build();

        Tenant savedTenant = tenantRepository.save(tenant);
        log.info("Tenant saved with id: {}", savedTenant.getId());

        // 2. Create admin user for this tenant
        User adminUser = User.builder()
                .name(request.getAdminUsername())
                .email(request.getAdminEmail())
                .password(passwordEncoder.encode(request.getAdminPassword()))
                .role(Role.ADMIN)
                .tenantId(savedTenant.getId())
                .isActive(true)
                .build();

        userRepository.save(adminUser);
        log.info("Admin user created for tenant id: {}", savedTenant.getId());

        TenantResponse response = tenantMapper.toResponse(savedTenant);
        response.setAdminUsername(request.getAdminUsername());
        response.setMessage("Tenant created successfully");

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantResponse> getAllTenants() {
        log.info("Fetching all tenants");
        return tenantMapper.toResponseList(tenantRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse getTenantById(Long id) {
        log.info("Fetching tenant by id: {}", id);
        return tenantMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional
    public TenantResponse updateTenant(Long id, UpdateTenantRequest request) {

        log.info("Updating tenant id: {}", id);

        Tenant tenant = findOrThrow(id);

        if (tenantRepository.existsByEmailAndIdNot(request.getEmail(), id)) {
            throw new DuplicateTenantException(
                    "Email already in use: " + request.getEmail());
        }

        tenantMapper.updateEntity(request, tenant);

        return tenantMapper.toResponse(tenantRepository.save(tenant));
    }

    @Override
    @Transactional
    public void deleteTenant(Long id) {

        log.info("Deleting tenant id: {}", id);

        Tenant tenant = findOrThrow(id);

        userRepository.deleteByTenantId(id);
        log.info("Admin user deleted for tenant id: {}", id);

        tenantRepository.delete(tenant);
        log.info("Tenant deleted: {}", id);
    }

    private Tenant findOrThrow(Long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new TenantNotFoundException(id));
    }
}