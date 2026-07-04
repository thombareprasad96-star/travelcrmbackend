package com.crm.travelcrm.settings.repository;

import com.crm.travelcrm.settings.entity.EmailMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface EmailMessageLogRepository extends JpaRepository<EmailMessageLog, Long> {

    long countByTenantIdAndSentAtAfter(Long tenantId, LocalDateTime after);

    long countByTenantIdAndStatusAndSentAtAfter(Long tenantId, String status, LocalDateTime after);
}