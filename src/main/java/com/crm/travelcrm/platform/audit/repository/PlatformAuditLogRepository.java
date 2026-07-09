package com.crm.travelcrm.platform.audit.repository;

import com.crm.travelcrm.platform.audit.entity.PlatformAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repository for the platform-level audit ledger. Platform-level (no tenant scoping). The filtered
 * read is a dynamic {@link org.springframework.data.jpa.domain.Specification} built in the service
 * (only non-null filters become predicates) — unlike a raw {@code :param IS NULL OR ...} query, it
 * never leaves an untyped bind marker for Postgres to reject on temporal/enum parameters. The write
 * path is {@code PlatformAuditRecorder}.
 */
@Repository
public interface PlatformAuditLogRepository
        extends JpaRepository<PlatformAuditLog, Long>, JpaSpecificationExecutor<PlatformAuditLog> {
}
