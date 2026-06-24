package com.crm.travelcrm.permission.repository;

import com.crm.travelcrm.permission.entity.PermissionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionTemplateRepository extends JpaRepository<PermissionTemplate, Long> {
    List<PermissionTemplate> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
    Optional<PermissionTemplate> findByTenantIdAndValue(Long tenantId, String value);
    boolean existsByTenantIdAndValue(Long tenantId, String value);
}