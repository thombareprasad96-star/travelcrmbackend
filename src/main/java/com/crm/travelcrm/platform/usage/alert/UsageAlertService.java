package com.crm.travelcrm.platform.usage.alert;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.notification.api.PlatformNotificationType;
import com.crm.travelcrm.platform.notification.api.PlatformNotifyEvent;
import com.crm.travelcrm.platform.usage.storage.TenantStorageAssetRepository;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Per-tenant usage-alert evaluation. Runs inside a tenant context (set by {@link UsageAlertScheduler})
 * and is {@code @Transactional} so the Hibernate tenant filter is engaged for the tenant-scoped count
 * queries. For each metric (users / bookings-this-month / storage) it warns the tenant's
 * admins/managers via a {@link NotifyEvent} as usage approaches or exceeds the limit; over-limit
 * crossings additionally raise a {@link PlatformNotifyEvent} to the SuperAdmin console and are
 * recorded on the platform audit log. The saved {@link UsageAlertMarker} is both the idempotency
 * guard and the alert history.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UsageAlertService {

    private static final int UNLIMITED_SENTINEL = 1_000_000;
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final TenantStorageAssetRepository storageAssetRepository;
    private final TravelerDocumentRepository travelerDocumentRepository;
    private final UsageAlertMarkerRepository markerRepository;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.usage.warn-threshold-percent:80}")
    private int warnThresholdPercent;

    @Transactional
    public int scanCurrentTenant(Long tenantId, LocalDate today) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return 0;

        String period = today.getYear() + "-" + String.format("%02d", today.getMonthValue());
        List<Long> recipients = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();

        int fired = 0;

        // Users (active seats)
        fired += evaluate(tenant, period, recipients, "USERS", "users",
                userRepository.countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(tenantId),
                effLong(tenant.getMaxUsers()), false);

        // Bookings this calendar month
        LocalDate monthStart = today.withDayOfMonth(1);
        fired += evaluate(tenant, period, recipients, "BOOKINGS", "bookings this month",
                bookingRepository.countByTenantForMonth(tenantId, monthStart, monthStart.plusMonths(1)),
                effLong(tenant.getMaxBookingsPerMonth()), false);

        // Storage (Cloudinary assets + traveler documents)
        Integer storageMb = (tenant.getMaxStorageMb() == null || tenant.getMaxStorageMb() <= 0)
                ? null : tenant.getMaxStorageMb();
        Long storageLimitBytes = storageMb == null ? null : storageMb * BYTES_PER_MB;
        long storageUsed = storageAssetRepository.sumBytesByTenant(tenantId)
                + travelerDocumentRepository.sumBytesByTenant(tenantId);
        fired += evaluate(tenant, period, recipients, "STORAGE", "storage",
                storageUsed, storageLimitBytes, true);

        return fired;
    }

    private int evaluate(Tenant tenant, String period, List<Long> recipients,
                         String metric, String label, long used, Long limit, boolean storage) {
        if (limit == null || limit <= 0) return 0;   // unlimited — nothing to alert on

        String level;
        if (used >= limit) {
            level = "OVER";
        } else if (used * 100.0 >= limit * (double) warnThresholdPercent) {
            level = "WARN";
        } else {
            return 0;
        }

        Long tenantId = tenant.getId();
        if (markerRepository.existsByTenantIdAndMetricAndPeriodAndLevel(tenantId, metric, period, level)) {
            return 0;   // already alerted this month at this level
        }
        markerRepository.save(UsageAlertMarker.builder()
                .tenantId(tenantId).metric(metric).period(period).level(level)
                .usedValue(used).limitValue(limit).build());

        String usedStr = storage ? formatBytes(used) : String.valueOf(used);
        String limitStr = storage ? formatBytes(limit) : String.valueOf(limit);
        long pct = Math.round(used * 100.0 / limit);
        boolean over = level.equals("OVER");
        String verb = over ? "exceeded" : "approaching";

        if (!recipients.isEmpty()) {
            eventPublisher.publishEvent(NotifyEvent.builder()
                    .type("USAGE_ALERT")
                    .tenantId(tenantId)
                    .recipientUserIds(recipients)
                    .title("Plan limit " + verb + " — " + label)
                    .message(capitalize(label) + " " + verb + ": " + usedStr + " of " + limitStr
                            + " (" + pct + "%). "
                            + (over ? "Upgrade your plan to avoid interruptions."
                                    : "Consider upgrading soon."))
                    .referenceType("TENANT")
                    .referencePublicId(tenant.getPublicId())
                    .channels(Set.of(DeliveryChannel.IN_APP))
                    .build());
        }

        if (over) {
            platformAuditRecorder.safeRecord(PlatformAuditAction.USAGE_LIMIT_EXCEEDED, true,
                    tenantId, tenant.getOrganizationCode(), "TENANT", tenant.getPublicId(),
                    capitalize(label) + " over limit: " + usedStr + " / " + limitStr);

            eventPublisher.publishEvent(PlatformNotifyEvent.builder()
                    .type(PlatformNotificationType.USAGE_LIMIT_EXCEEDED)
                    .title("Plan limit exceeded — " + label)
                    .message(tenant.getOrganizationName() + " is over its plan limit for " + label
                            + ": " + usedStr + " of " + limitStr + " (" + pct + "%)")
                    .referenceType(PlatformNotificationType.REF_TENANT)
                    .referencePublicId(tenant.getPublicId())
                    .tenantId(tenantId)
                    .tenantName(tenant.getOrganizationName())
                    .tenantPublicId(tenant.getPublicId())
                    .build());
        }

        log.info("[USAGE-ALERT] tenant={} metric={} level={} used={} limit={}",
                tenantId, metric, level, used, limit);
        return 1;
    }

    private static Long effLong(Integer raw) {
        return (raw == null || raw <= 0 || raw >= UNLIMITED_SENTINEL) ? null : (long) raw;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return Math.round(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}