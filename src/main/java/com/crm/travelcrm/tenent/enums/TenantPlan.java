// Add TenantPlan enum
package com.crm.travelcrm.tenent.enums;

public enum TenantPlan {
    STARTER,    //  5 users, basic features
    PRO,        // 20 users, advanced reporting
    ENTERPRISE, // unlimited users, all features

    /**
     * Vehicle Diary sold on its own — modules = {FLEET} and nothing else.
     *
     * <p>Not a cheaper CRM. A tenant on this plan is a transport operator who bought fleet
     * management, and {@code ModuleAccessFilter} closes every CRM path for them — that filter IS
     * the mechanism behind "a Fleet-only user cannot reach CRM endpoints even by calling the API
     * by hand". There is deliberately no {@code ProductFamily} column: the module set already
     * says which product this is.
     *
     * <p><b>Adding a value here is not free.</b> Seven CHECK constraints name this enum
     * ({@code plans.code}, {@code tenants.plan}, {@code subscriptions.plan_code},
     * {@code billing_records.plan} + {@code .upgrade_to_plan}, {@code upgrade_requests.current_plan}
     * + {@code .requested_plan}). V2 PART 12 widens all seven in one loop — miss one and the INSERT
     * fails at whichever step first writes the new value.
     */
    FLEET
}