package com.crm.travelcrm.settings.repository;

import com.crm.travelcrm.settings.entity.WaMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface WaMessageLogRepository extends JpaRepository<WaMessageLog, Long> {

    long countByTenantIdAndSentAtAfter(Long tenantId, LocalDateTime after);

    long countByTenantIdAndStatusAndSentAtAfter(Long tenantId, String status, LocalDateTime after);
}