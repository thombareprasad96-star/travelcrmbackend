package com.crm.travelcrm.platform.entitlement.service;

import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.entitlement.dto.MyEntitlementsResponse;
import com.crm.travelcrm.platform.entitlement.dto.TenantModulesResponse;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantEntitlementServiceImpl implements TenantEntitlementService {

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    // Short-TTL cache so the per-request ModuleAccessFilter doesn't hit the DB on every call.
    // Evicted immediately on updateModules; a plan change self-heals within the TTL.
    private static final long CACHE_TTL_MS = 30_000;
    private final Map<Long, CachedModules> moduleCache = new ConcurrentHashMap<>();
    private record CachedModules(Set<String> modules, long expiresAt) {}

    @Override
    @Transactional(readOnly = true)
    public TenantModulesResponse getModules(UUID tenantPublicId) {
        Tenant tenant = requireTenant(tenantPublicId);
        return new TenantModulesResponse(
                availableModules(),
                new TreeSet<>(effectiveModules(tenant)),
                new TreeSet<>(planModules(tenant.getPlan())));
    }

    @Override
    @Transactional
    public TenantModulesResponse updateModules(UUID tenantPublicId, Set<String> modules) {
        Tenant tenant = requireTenant(tenantPublicId);
        Set<String> available = availableModules();
        // Whitelist against the catalogue so a stray key can never be persisted.
        Set<String> sanitized = (modules == null ? Set.<String>of() : modules).stream()
                .filter(available::contains)
                .collect(Collectors.toCollection(HashSet::new));

        tenant.setEnabledModules(sanitized);
        tenantRepository.save(tenant);
        moduleCache.remove(tenant.getId());   // reflect the change immediately in the access filter

        platformAuditRecorder.safeRecord(PlatformAuditAction.FEATURE_FLAG_CHANGE, true,
                tenant.getId(), tenant.getOrganizationCode(), "TENANT", tenant.getPublicId(),
                "Set modules for " + tenant.getOrganizationName() + " = " + new TreeSet<>(sanitized));

        return new TenantModulesResponse(available, new TreeSet<>(sanitized),
                new TreeSet<>(planModules(tenant.getPlan())));
    }

    @Override
    @Transactional(readOnly = true)
    public MyEntitlementsResponse entitlementsForTenant(Long tenantId) {
        if (tenantId == null) {
            return new MyEntitlementsResponse(Set.of(), null, null);
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(null));
        return new MyEntitlementsResponse(
                new TreeSet<>(effectiveModulesFor(tenantId)), tenant.getMaxUsers(), tenant.getMaxLeads());
    }

    @Override
    public Set<String> effectiveModulesFor(Long tenantId) {
        if (tenantId == null) return Set.of();
        long now = System.currentTimeMillis();
        CachedModules cached = moduleCache.get(tenantId);
        if (cached != null && cached.expiresAt() > now) return cached.modules();
        Set<String> mods = Set.copyOf(computeEffectiveModules(tenantId));
        moduleCache.put(tenantId, new CachedModules(mods, now + CACHE_TTL_MS));
        return mods;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Effective set for the filter, resolved by id: the tenant's own overrides, or (when it has none)
     * the plan defaults. Uses a direct element-collection query — no lazy Tenant collection to init.
     */
    private Set<String> computeEffectiveModules(Long tenantId) {
        Set<String> enabled = new HashSet<>(tenantRepository.findEnabledModules(tenantId));
        if (!enabled.isEmpty()) return enabled;
        return tenantRepository.findById(tenantId)
                .map(t -> planModules(t.getPlan()))
                .orElseGet(Set::of);
    }

    /** Effective = the tenant's own set, or (for pre-existing tenants with none) the plan defaults. */
    private Set<String> effectiveModules(Tenant tenant) {
        Set<String> enabled = tenant.getEnabledModules();
        return (enabled != null && !enabled.isEmpty()) ? new HashSet<>(enabled) : planModules(tenant.getPlan());
    }

    private Set<String> planModules(TenantPlan plan) {
        return planRepository.findByCode(plan).map(Plan::getModules)
                .map(HashSet::new).orElseGet(HashSet::new);
    }

    /** Full module catalogue = union of every plan's modules. */
    private Set<String> availableModules() {
        return planRepository.findAll().stream()
                .flatMap(p -> p.getModules().stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Tenant requireTenant(UUID publicId) {
        return tenantRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));
    }
}
