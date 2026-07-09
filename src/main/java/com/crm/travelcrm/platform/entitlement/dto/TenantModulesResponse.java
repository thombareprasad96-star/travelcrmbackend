package com.crm.travelcrm.platform.entitlement.dto;

import java.util.Set;

/**
 * A tenant's module access for the console editor. {@code available} = full catalogue,
 * {@code enabled} = the tenant's effective set, {@code planModules} = its plan's defaults
 * (shown so the SuperAdmin can see what deviates from the plan).
 */
public record TenantModulesResponse(Set<String> available, Set<String> enabled, Set<String> planModules) {
}