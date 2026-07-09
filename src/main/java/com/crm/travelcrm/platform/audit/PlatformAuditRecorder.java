package com.crm.travelcrm.platform.audit;

import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditLog;
import com.crm.travelcrm.platform.audit.repository.PlatformAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes {@link PlatformAuditLog} rows for god-mode operations. Mirrors the tenant-side
 * {@code ActivityLogRecorder}: every persist runs in its <b>own</b> transaction
 * ({@link Propagation#REQUIRES_NEW}) so the audit write commits independently of — and never
 * rolls back — the operation that triggered it.
 *
 * <p>Callers use {@code safeRecord(...)} (best-effort, swallows failures) so an audit-write
 * problem can never break the actual platform operation. Use the explicit-actor overload at
 * login (before {@link PlatformContext} is populated); use the context overload everywhere
 * else (it resolves the acting SuperAdmin from {@link PlatformContext}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformAuditRecorder {

    private final PlatformAuditLogRepository repository;

    /** Persist one audit row in a fresh transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PlatformAuditLog entry) {
        repository.save(entry);
    }

    /**
     * Explicit-actor best-effort write. Used at the login boundary, where the acting
     * SuperAdmin is known directly and {@link PlatformContext} is not yet set (and, for a
     * failed login, {@code superAdminId} may legitimately be null).
     */
    public void safeRecord(PlatformAuditAction action, boolean success,
                           Long superAdminId, String actorEmail,
                           Long targetTenantId, String targetTenantCode,
                           String targetType, UUID targetPublicId,
                           String description, String ipAddress, String userAgent) {
        if (action == null) {
            return;
        }
        try {
            record(PlatformAuditLog.builder()
                    .action(action)
                    .success(success)
                    .superAdminId(superAdminId)
                    .actorEmail(actorEmail)
                    .targetTenantId(targetTenantId)
                    .targetTenantCode(targetTenantCode)
                    .targetType(targetType)
                    .targetPublicId(targetPublicId)
                    .description(description)
                    .ipAddress(ipAddress)
                    .userAgent(truncate(userAgent, 500))
                    .build());
        } catch (Exception ex) {
            log.warn("Platform audit write failed ({}): {}", action, ex.getMessage());
        }
    }

    /**
     * Context-resolved best-effort write for authenticated platform operations — the acting
     * SuperAdmin is taken from {@link PlatformContext}. Use this from every platform service
     * (tenant lifecycle, plan changes, impersonation, etc.) in the later phases.
     */
    public void safeRecord(PlatformAuditAction action, boolean success,
                           Long targetTenantId, String targetTenantCode,
                           String targetType, UUID targetPublicId,
                           String description) {
        PlatformActor actor = PlatformContext.getActor();
        safeRecord(action, success,
                actor != null ? actor.superAdminId() : null,
                actor != null ? actor.email() : null,
                targetTenantId, targetTenantCode, targetType, targetPublicId,
                description, null, null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
