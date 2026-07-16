package com.crm.travelcrm.accounting.settings.repository;

import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountingSettingsRepository extends JpaRepository<AccountingSettings, Long> {

    Optional<AccountingSettings> findByTenantId(Long tenantId);
}