package com.crm.travelcrm.ai.repository;

import com.crm.travelcrm.ai.entity.AiAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAuditLogRepository extends JpaRepository<AiAuditLog, Long> {
}