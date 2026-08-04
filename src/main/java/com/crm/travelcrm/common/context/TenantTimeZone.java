package com.crm.travelcrm.common.context;

import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the zone a tenant's business day is measured in.
 *
 * <p><b>Why this exists.</b> Every date boundary in the app used {@code ZoneId.systemDefault()} —
 * the SERVER's zone, not the customer's. That is harmless while the product only stores dates a
 * user typed, and becomes a correctness bug the moment money carries a document date. The fleet
 * ledger is India + Nepal: IST is +5:30, NPT is +5:45. A Bhansar receipt paid at 23:50 NPT lands on
 * the previous day in every IST report; a self-hosted VPS on a different TZ produces different
 * numbers from the SaaS for byte-identical data.
 *
 * <p><b>Deliberately introduced before the ledger exists.</b> Adding a tenant zone after financial
 * rows are written is a backfill with no correct answer — you cannot recover which local day a
 * stored instant belonged to. See {@code docs/FLEET_MODULE_REDESIGN.md} §4.11.
 *
 * <p>Short-TTL cache, same shape as {@code TenantEntitlementServiceImpl.moduleCache}: this is read
 * on every date-bounded query, so it must not hit the DB each time. The TTL matters more than the
 * eviction here — a zone can legitimately be changed by hand in SQL during a migration, and a
 * 30-second self-heal means no restart is needed. {@link #evict(Long)} makes an API-driven change
 * immediate.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TenantTimeZone {

    /** Used when no tenant is bound, the tenant is gone, or its stored zone is unparseable. */
    public static final ZoneId DEFAULT = ZoneId.of("Asia/Kolkata");

    private final TenantRepository tenantRepository;

    // Short-TTL cache (house pattern — see TenantEntitlementServiceImpl). Evicted immediately when
    // the zone is changed through the API; a change applied directly in SQL self-heals within the TTL.
    private static final long CACHE_TTL_MS = 30_000;
    private final Map<Long, CachedZone> cache = new ConcurrentHashMap<>();
    private record CachedZone(ZoneId zone, long expiresAt) {}

    /**
     * Zone of the tenant bound to the current request/scheduler thread.
     * Falls back to {@link #DEFAULT} when {@link TenantContext} is empty (SuperAdmin, portal,
     * pre-auth) rather than throwing — a date boundary must never 500.
     */
    public ZoneId current() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? DEFAULT : forTenant(tenantId);
    }

    public ZoneId forTenant(Long tenantId) {
        if (tenantId == null) return DEFAULT;
        long now = System.currentTimeMillis();
        CachedZone cached = cache.get(tenantId);
        if (cached != null && cached.expiresAt() > now) return cached.zone();

        ZoneId zone = load(tenantId);
        cache.put(tenantId, new CachedZone(zone, now + CACHE_TTL_MS));
        return zone;
    }

    /** "Today" as the tenant's office would say it — the only correct basis for a day/month report. */
    public LocalDate today() {
        return LocalDate.now(current());
    }

    public LocalDate todayFor(Long tenantId) {
        return LocalDate.now(forTenant(tenantId));
    }

    public LocalDateTime now() {
        return LocalDateTime.now(current());
    }

    /** A {@link Clock} for services that want to stay testable instead of calling {@code now()}. */
    public Clock clock() {
        return Clock.system(current());
    }

    /** Call after a tenant's timezone is changed so the new zone applies immediately, not in 30s. */
    public void evict(Long tenantId) {
        if (tenantId != null) cache.remove(tenantId);
    }

    public void evictAll() {
        cache.clear();
    }

    private ZoneId load(Long tenantId) {
        String stored = tenantRepository.findById(tenantId)   // platform-level lookup: intentionally not tenant-scoped
                .map(Tenant::getTimezone)
                .orElse(null);
        if (stored == null || stored.isBlank()) return DEFAULT;
        try {
            return ZoneId.of(stored.trim());
        } catch (RuntimeException e) {
            // A bad value must degrade to IST, never break every dated query for that tenant.
            log.warn("Tenant {} has an unparseable timezone '{}' — falling back to {}",
                    tenantId, stored, DEFAULT);
            return DEFAULT;
        }
    }
}
