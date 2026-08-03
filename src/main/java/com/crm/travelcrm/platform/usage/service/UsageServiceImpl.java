package com.crm.travelcrm.platform.usage.service;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.platform.usage.dto.TenantUsageResponse;
import com.crm.travelcrm.platform.usage.dto.UpdateTenantQuotaRequest;
import com.crm.travelcrm.platform.usage.dto.UsageDashboardResponse;
import com.crm.travelcrm.platform.usage.dto.UsageOverviewResponse;
import com.crm.travelcrm.platform.usage.storage.TenantStorageAssetRepository;
import com.crm.travelcrm.fleet.repository.FleetAttachmentRepository;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes per-tenant usage (active users, bookings this month, storage) against each tenant's
 * effective plan/override limits, and lets a SuperAdmin override those limits. Counts come from
 * grouped, single-query aggregates so there is no per-tenant N+1; the tenant list itself is the only
 * bounded (platform-scale) set loaded, matching {@code AnalyticsServiceImpl}. All queries run from
 * the SuperAdmin thread where {@code TenantContext} is null, so the Hibernate tenant filter is off.
 */
@Service
@RequiredArgsConstructor
public class UsageServiceImpl implements UsageService {

    /** Historical "unlimited" sentinel used for {@code Tenant.maxUsers} (see TenantServiceImpl.changePlan). */
    private static final int UNLIMITED_SENTINEL = 1_000_000;
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final TenantStorageAssetRepository storageAssetRepository;
    private final TravelerDocumentRepository travelerDocumentRepository;
    private final FleetAttachmentRepository fleetAttachmentRepository;
    private final SubAgentProfileRepository subAgentProfileRepository;
    private final PlanRepository planRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Value("${app.usage.warn-threshold-percent:80}")
    private int warnThresholdPercent;

    @Override
    @Transactional(readOnly = true)
    public UsageDashboardResponse dashboard() {
        List<Tenant> tenants = tenantRepository.findByDeletedAtIsNull();
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        Map<Long, Long> activeUsers = toCountMap(userRepository.countActiveUsersByTenant());
        Map<Long, Long> bookings =
                toCountMap(bookingRepository.countBookingsByTenantForMonth(monthStart, nextMonthStart));
        Map<Long, Long> assetBytes = toCountMap(storageAssetRepository.sumBytesGroupedByTenant());
        Map<Long, Long> docBytes = toCountMap(travelerDocumentRepository.sumBytesGroupedByTenant());
        Map<Long, Long> subAgents = toCountMap(subAgentProfileRepository.countProvisionedGroupedByTenant());

        List<TenantUsageResponse> rows = new ArrayList<>(tenants.size());
        for (Tenant t : tenants) {
            long u = activeUsers.getOrDefault(t.getId(), 0L);
            long b = bookings.getOrDefault(t.getId(), 0L);
            long s = assetBytes.getOrDefault(t.getId(), 0L) + docBytes.getOrDefault(t.getId(), 0L);
            long sa = subAgents.getOrDefault(t.getId(), 0L);
            rows.add(toUsage(t, u, b, s, sa));
        }
        // Worst-usage first so at-risk tenants surface at the top of the dashboard.
        rows.sort(Comparator.comparingDouble(UsageServiceImpl::worstPercent).reversed());

        long over = rows.stream().filter(UsageServiceImpl::anyOver).count();
        long near = rows.stream().filter(r -> !anyOver(r) && anyNear(r)).count();

        UsageOverviewResponse overview = UsageOverviewResponse.builder()
                .totalTenants(rows.size())
                .tenantsOverLimit(over)
                .tenantsNearLimit(near)
                .totalActiveUsers(rows.stream().mapToLong(TenantUsageResponse::getActiveUsers).sum())
                .totalBookingsThisMonth(rows.stream().mapToLong(TenantUsageResponse::getBookingsThisMonth).sum())
                .totalStorageBytes(rows.stream().mapToLong(TenantUsageResponse::getStorageUsedBytes).sum())
                .warnThresholdPercent(warnThresholdPercent)
                .build();

        return UsageDashboardResponse.builder().overview(overview).tenants(rows).build();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantUsageResponse getUsage(UUID tenantPublicId) {
        Tenant t = requireLive(tenantPublicId);
        return toUsage(t, currentUsers(t.getId()), currentBookings(t.getId()), currentStorage(t.getId()),
                currentSubAgents(t.getId()));
    }

    @Override
    @Transactional
    public TenantUsageResponse overrideQuota(UUID tenantPublicId, UpdateTenantQuotaRequest req) {
        Tenant t = requireLive(tenantPublicId);

        String detail;
        if (req.isClearOverride()) {
            planRepository.findByCode(t.getPlan()).ifPresent(p -> {
                t.setMaxUsers(p.getMaxUsers() != null ? p.getMaxUsers() : UNLIMITED_SENTINEL);
                t.setMaxLeads(p.getMaxLeads());
                t.setMaxBookingsPerMonth(p.getMaxBookingsPerMonth());
                t.setMaxStorageMb(p.getMaxStorageMb());
                // Keep purchased add-on sub-agent seats: cap = plan default + paid seats (never wipe
                // seats the tenant paid a one-time license fee for — mirrors TenantPlanApplier.applyPlan).
                int planSeats = p.getMaxSubAgents() != null ? Math.max(0, p.getMaxSubAgents()) : 0;
                int purchasedSeats = t.getPurchasedSubAgentSeats() != null ? Math.max(0, t.getPurchasedSubAgentSeats()) : 0;
                t.setMaxSubAgents(planSeats + purchasedSeats);
            });
            t.setQuotaOverride(false);
            detail = "Cleared quota override — reverted to " + t.getPlan() + " plan defaults";
        } else {
            boolean any = false;
            if (req.getMaxUsers() != null) { t.setMaxUsers(req.getMaxUsers()); any = true; }
            if (req.getMaxLeads() != null) { t.setMaxLeads(req.getMaxLeads()); any = true; }
            if (req.getMaxBookingsPerMonth() != null) { t.setMaxBookingsPerMonth(req.getMaxBookingsPerMonth()); any = true; }
            if (req.getMaxStorageMb() != null) { t.setMaxStorageMb(req.getMaxStorageMb()); any = true; }
            if (req.getMaxSubAgents() != null) { t.setMaxSubAgents(req.getMaxSubAgents()); any = true; }
            if (!any) {
                throw new BusinessException(
                        "Provide at least one limit to override, or set clearOverride=true.",
                        HttpStatus.BAD_REQUEST);
            }
            t.setQuotaOverride(true);
            detail = "Quota override — users=" + t.getMaxUsers() + ", leads=" + t.getMaxLeads()
                    + ", bookings/mo=" + t.getMaxBookingsPerMonth() + ", storageMb=" + t.getMaxStorageMb()
                    + ", subAgents=" + t.getMaxSubAgents();
        }

        tenantRepository.save(t);
        platformAuditRecorder.safeRecord(PlatformAuditAction.QUOTA_OVERRIDE, true,
                t.getId(), t.getOrganizationCode(), "TENANT", t.getPublicId(), detail);

        return toUsage(t, currentUsers(t.getId()), currentBookings(t.getId()), currentStorage(t.getId()),
                currentSubAgents(t.getId()));
    }

    // ── single-tenant live counts ───────────────────────────────────────────────

    private long currentUsers(Long tenantId) {
        return userRepository.countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(tenantId);
    }

    private long currentBookings(Long tenantId) {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        return bookingRepository.countByTenantForMonth(tenantId, monthStart, monthStart.plusMonths(1));
    }

    private long currentStorage(Long tenantId) {
        return storageAssetRepository.sumBytesByTenant(tenantId)
                + travelerDocumentRepository.sumBytesByTenant(tenantId)
                + fleetAttachmentRepository.sumBytesByTenant(tenantId);
    }

    private long currentSubAgents(Long tenantId) {
        return subAgentProfileRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
    }

    private Tenant requireLive(UUID publicId) {
        return tenantRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));
    }

    // ── mapping + math ──────────────────────────────────────────────────────────

    private TenantUsageResponse toUsage(Tenant t, long activeUsers, long bookings, long storageBytes,
                                        long subAgents) {
        Long userLimit = effLong(t.getMaxUsers());
        Long bookingLimit = effLong(t.getMaxBookingsPerMonth());
        Integer storageMb = (t.getMaxStorageMb() == null || t.getMaxStorageMb() <= 0) ? null : t.getMaxStorageMb();
        Long storageLimitBytes = storageMb == null ? null : storageMb * BYTES_PER_MB;

        // Sub-agents are GATED (0 = none allowed, NOT unlimited), so they don't use effLong: the cap
        // is always shown as a concrete number and "over" fires if a tenant somehow holds seats it is
        // no longer entitled to.
        int saCap = t.getMaxSubAgents() == null ? 0 : Math.max(0, t.getMaxSubAgents());
        Double saPercent = saCap > 0 ? Math.round((subAgents * 10000.0) / saCap) / 100.0 : null;
        boolean saOver = saCap > 0 ? subAgents >= saCap : subAgents > 0;
        boolean saNear = saCap > 0 && subAgents < saCap && subAgents * 100.0 >= saCap * (double) warnThresholdPercent;

        return TenantUsageResponse.builder()
                .tenantPublicId(t.getPublicId())
                .organizationName(t.getOrganizationName())
                .organizationCode(t.getOrganizationCode())
                .plan(t.getPlan())
                .status(t.getStatus())
                .quotaOverride(t.isQuotaOverride())
                .activeUsers(activeUsers)
                .maxUsers(userLimit == null ? null : userLimit.intValue())
                .usersPercent(pct(activeUsers, userLimit))
                .usersOverLimit(over(activeUsers, userLimit))
                .usersNearLimit(near(activeUsers, userLimit, warnThresholdPercent))
                .bookingsThisMonth(bookings)
                .maxBookingsPerMonth(bookingLimit == null ? null : bookingLimit.intValue())
                .bookingsPercent(pct(bookings, bookingLimit))
                .bookingsOverLimit(over(bookings, bookingLimit))
                .bookingsNearLimit(near(bookings, bookingLimit, warnThresholdPercent))
                .storageUsedBytes(storageBytes)
                .maxStorageMb(storageMb)
                .storagePercent(pct(storageBytes, storageLimitBytes))
                .storageOverLimit(over(storageBytes, storageLimitBytes))
                .storageNearLimit(near(storageBytes, storageLimitBytes, warnThresholdPercent))
                .subAgents(subAgents)
                .maxSubAgents(saCap)
                .subAgentsPercent(saPercent)
                .subAgentsOverLimit(saOver)
                .subAgentsNearLimit(saNear)
                .build();
    }

    private static boolean anyOver(TenantUsageResponse r) {
        return r.isUsersOverLimit() || r.isBookingsOverLimit() || r.isStorageOverLimit();
    }

    private static boolean anyNear(TenantUsageResponse r) {
        return r.isUsersNearLimit() || r.isBookingsNearLimit() || r.isStorageNearLimit();
    }

    private static double worstPercent(TenantUsageResponse r) {
        double m = -1;
        if (r.getUsersPercent() != null) m = Math.max(m, r.getUsersPercent());
        if (r.getBookingsPercent() != null) m = Math.max(m, r.getBookingsPercent());
        if (r.getStoragePercent() != null) m = Math.max(m, r.getStoragePercent());
        return m;
    }

    /** Normalizes a raw stored limit to null=unlimited (null, ≤0, or the users unlimited-sentinel). */
    private static Long effLong(Integer raw) {
        return (raw == null || raw <= 0 || raw >= UNLIMITED_SENTINEL) ? null : (long) raw;
    }

    private static Double pct(long used, Long limit) {
        if (limit == null || limit <= 0) return null;
        return Math.round((used * 10000.0) / limit) / 100.0;   // 2-decimal percent
    }

    private static boolean over(long used, Long limit) {
        return limit != null && limit > 0 && used >= limit;
    }

    private static boolean near(long used, Long limit, int warnPct) {
        if (limit == null || limit <= 0) return false;
        return used < limit && used * 100.0 >= limit * (double) warnPct;
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            m.put(((Number) r[0]).longValue(), r[1] == null ? 0L : ((Number) r[1]).longValue());
        }
        return m;
    }
}