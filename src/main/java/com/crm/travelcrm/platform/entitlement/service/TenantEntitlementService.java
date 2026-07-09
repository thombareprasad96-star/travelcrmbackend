package com.crm.travelcrm.platform.entitlement.service;

import com.crm.travelcrm.platform.entitlement.dto.MyEntitlementsResponse;
import com.crm.travelcrm.platform.entitlement.dto.TenantModulesResponse;

import java.util.Set;
import java.util.UUID;

/** Per-tenant module entitlements (Feature Flags). */
public interface TenantEntitlementService {

    TenantModulesResponse getModules(UUID tenantPublicId);

    TenantModulesResponse updateModules(UUID tenantPublicId, Set<String> modules);

    /** Effective entitlements for a tenant by internal id — for the tenant-facing endpoint. */
    MyEntitlementsResponse entitlementsForTenant(Long tenantId);

    /**
     * The tenant's effective module keys (own overrides, else plan defaults), cached briefly for the
     * per-request {@code ModuleAccessFilter}. Never throws; an unknown/empty tenant yields an empty set.
     */
    Set<String> effectiveModulesFor(Long tenantId);
}