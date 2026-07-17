package com.crm.travelcrm.common.feature;

/**
 * Deployment-level feature switches the frontend needs in order to render the right shell.
 *
 * <p>Deliberately NOT part of {@code /api/me/entitlements}: entitlements answer "what has this
 * tenant PAID for" (per-tenant, DB-driven, enforced by {@code ModuleAccessFilter}), whereas these
 * answer "what is switched on in THIS deployment at all" (env-driven, identical for every tenant on
 * the box). Folding a build/deploy flag into the plan model would imply a tenant could buy their way
 * into a feature the server simply is not running.
 *
 * @param disha whether the Disha AI assistant is deployed. False in production for this sprint, so
 *              the frontend hides the chat widget entirely rather than rendering a button that 404s.
 */
public record FeatureFlagsResponse(boolean disha) {
}