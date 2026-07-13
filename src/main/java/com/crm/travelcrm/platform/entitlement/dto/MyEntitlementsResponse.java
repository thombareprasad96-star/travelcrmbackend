package com.crm.travelcrm.platform.entitlement.dto;

import java.util.Set;

/** The current tenant's effective entitlements — consumed by the tenant app to soft-gate modules. */
public record MyEntitlementsResponse(Set<String> modules, Integer maxUsers, Integer maxLeads,
                                     Integer maxBookingsPerMonth, Integer maxStorageMb) {
}