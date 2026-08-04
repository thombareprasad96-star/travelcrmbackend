package com.crm.travelcrm.platform.entitlement.dto;

import java.util.Set;

/**
 * The current tenant's effective entitlements — consumed by the tenant app to soft-gate modules.
 *
 * @param productMode which product this DEPLOYMENT is ({@code CRM_SUITE} / {@code FLEET_STANDALONE}).
 *                    Distinct from {@code modules}, and the client needs both: {@code modules} says
 *                    what THIS TENANT bought, while this says whether a CRM exists here at all. A
 *                    fleet-only tenant on a CRM deployment gets the fleet sidebar; a
 *                    FLEET_STANDALONE deployment additionally drops the CRM branding and the
 *                    "upgrade to see more modules" affordances, because there is nothing to upgrade
 *                    to. Read this rather than {@code /api/me/features}, which is a single
 *                    deployment-wide boolean about the AI assistant and answers a different question.
 */
public record MyEntitlementsResponse(Set<String> modules, Integer maxUsers, Integer maxLeads,
                                     Integer maxBookingsPerMonth, Integer maxStorageMb,
                                     String productMode) {
}