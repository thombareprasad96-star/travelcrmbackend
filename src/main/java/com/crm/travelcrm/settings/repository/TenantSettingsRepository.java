package com.crm.travelcrm.settings.repository;

import com.crm.travelcrm.settings.entity.TenantSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantSettingsRepository extends JpaRepository<TenantSettings, Long> {

    // One settings row per tenant. Tenant-scoped finder (never bare findById).
    Optional<TenantSettings> findByTenantId(Long tenantId);
}