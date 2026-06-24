package com.crm.travelcrm.common.staffip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantStaffIpRepository extends JpaRepository<TenantStaffIp, Long> {
    Optional<TenantStaffIp> findByTenantIdAndIpAddress(Long tenantId, String ipAddress);
    boolean existsByTenantIdAndIpAddress(Long tenantId, String ipAddress);
}