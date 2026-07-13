package com.crm.travelcrm.tenent.enums;

/**
 * Tenant lifecycle. Operational states (staff can log in / operate) are {@code ACTIVE},
 * {@code TRIAL} and {@code PAST_DUE} (the dunning grace window); {@code SUSPENDED} and
 * {@code EXPIRED} block the tenant's users — see {@code Tenant.isOperational()}.
 */
public enum TenantStatus {
    ACTIVE,
    TRIAL,
    /**
     * Dunning grace: an invoice is overdue / a payment failed, but the tenant keeps working for the
     * configurable grace window ({@code app.subscription.grace-days}) with warnings. Paying clears it
     * back to {@code ACTIVE}; letting the grace lapse flips it to {@code EXPIRED}. Still operational.
     */
    PAST_DUE,
    SUSPENDED,
    EXPIRED,
    /**
     * Deprecated legacy value — retained so pre-existing rows still deserialize under
     * {@code ddl-auto=update}. Treated as non-operational everywhere. Do not assign to new tenants.
     */
    @Deprecated
    INACTIVE
}