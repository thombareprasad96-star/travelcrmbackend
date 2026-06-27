package com.crm.travelcrm.activity.audit;

import com.crm.travelcrm.activity.entity.ActivityAction;
import com.crm.travelcrm.activity.entity.ActivityLog;
import com.crm.travelcrm.activity.repository.ActivityLogRepository;
import com.crm.travelcrm.auth.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit-trail rows. Every persist runs in its <b>own</b> transaction
 * ({@link Propagation#REQUIRES_NEW}) so the audit write commits independently of — and never
 * rolls back — the business request that triggered it.
 *
 * <p>Callers should use {@link #safeRecord} (best-effort, swallows failures) so a logging problem
 * can never break the user's actual operation. The acting user is referenced by a logical FK plus
 * denormalised name/email/role-label snapshots.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogRecorder {

    private final ActivityLogRepository repository;

    /**
     * Persist one audit row in a fresh transaction. Public (not self-invoked) so the
     * {@code REQUIRES_NEW} proxy actually applies.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ActivityLog entry) {
        repository.save(entry);
    }

    /**
     * Best-effort audit write — builds the row and persists it, swallowing any failure.
     * No-ops when {@code tenantId} is null (platform/anonymous actions are not tenant-scoped
     * and {@link ActivityLog} requires a tenant).
     */
    public void safeRecord(ActivityAction action, String description,
                           Long userId, String userName, String userEmail, String userType,
                           Long tenantId, String ipAddress, String userAgent) {
        if (tenantId == null || action == null) {
            return;
        }
        try {
            record(ActivityLog.builder()
                    .tenantId(tenantId)
                    .actingUserId(userId)
                    .userName(userName)
                    .userEmail(userEmail)
                    .userType(userType)
                    .action(action)
                    .description(description)
                    .ipAddress(ipAddress)
                    .userAgent(truncate(userAgent, 500))
                    .build());
        } catch (Exception ex) {
            log.warn("Activity audit write failed ({}): {}", action, ex.getMessage());
        }
    }

    /** Maps a tenant role to the display label the Activity Reports filter by. */
    public static String labelFor(Role role) {
        if (role == null) {
            return "User";
        }
        return switch (role) {
            case TENANT_ADMIN -> "Admin";
            case MANAGER      -> "Manager";
            case STAFF        -> "Staff";
            case ACCOUNTANT   -> "Accountant";
            case TRAVEL_AGENT -> "Agent";
            case SUPERADMIN   -> "SuperAdmin";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}