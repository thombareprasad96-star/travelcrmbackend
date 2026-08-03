package com.crm.travelcrm.common.config;

/**
 * Which product this deployment IS. Bound from {@code app.product-mode}
 * (env {@code APP_PRODUCT_MODE}); defaults to {@link #CRM_SUITE} so an existing install that never
 * sets it keeps behaving exactly as before.
 *
 * <p><b>One codebase, two products.</b> The mode is what the fleet integration adapters select on:
 * {@code CrmBookingJobReferenceAdapter} and {@code CrmVendorPartyAdapter} are
 * {@code @ConditionalOnProperty(havingValue = "CRM_SUITE", matchIfMissing = true)}, and the
 * standalone ports take over by {@code @ConditionalOnMissingBean}. A misconfigured value therefore
 * fails SAFE — an unrecognised mode is not CRM_SUITE, so the standalone adapters win and fleet runs
 * without ever reading a CRM table. The reverse (silently falling back to reading bookings and
 * vendors in a deployment that has neither) would be the dangerous default.
 *
 * <p><b>What the mode does NOT do:</b> it does not gate any endpoint. Per-tenant module
 * entitlements do that — a Fleet-only tenant is one on the {@code FLEET} plan, whose module set is
 * {@code {FLEET}}, and {@code ModuleAccessFilter} closes every CRM path for it. The two signals are
 * independent on purpose: a CRM_SUITE deployment can still sell a fleet-only tenant, and this flag
 * says "there is no CRM here at all", which is a branding and provisioning fact rather than an
 * authorisation one.
 */
public enum ProductMode {

    /** The full Travel CRM, with Vehicle Diary as one module among many. Default. */
    CRM_SUITE,

    /** Vehicle Diary sold on its own. No CRM modules exist in this deployment. */
    FLEET_STANDALONE;

    /** Lenient parse — an unknown or blank value degrades to standalone, which is the safe side. */
    public static ProductMode from(String raw) {
        if (raw == null || raw.isBlank()) return CRM_SUITE;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FLEET_STANDALONE;
        }
    }
}
