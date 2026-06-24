package com.crm.travelcrm.common.staffip;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records the IPs a tenant's staff log in from (the "home IP" set) and answers whether a given
 * IP belongs to it. Drives HOME vs EXTERNAL classification of public quotation views.
 *
 * <p>All writes are best-effort and must never break or slow login.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StaffIpService {

    private final TenantStaffIpRepository repository;

    /** Best-effort upsert of a staff login IP. Never throws. */
    @Transactional
    public void recordStaffIp(Long tenantId, String ipAddress) {
        if (tenantId == null || ipAddress == null || ipAddress.isBlank()) return;
        try {
            TenantStaffIp row = repository.findByTenantIdAndIpAddress(tenantId, ipAddress)
                    .orElseGet(() -> TenantStaffIp.builder()
                            .tenantId(tenantId).ipAddress(ipAddress).build());
            row.setLastSeenAt(Instant.now());
            repository.save(row);
        } catch (Exception e) {
            log.warn("Failed to record staff IP {} for tenant {}: {}", ipAddress, tenantId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public boolean isHomeIp(Long tenantId, String ipAddress) {
        if (tenantId == null || ipAddress == null) return false;
        return repository.existsByTenantIdAndIpAddress(tenantId, ipAddress);
    }
}