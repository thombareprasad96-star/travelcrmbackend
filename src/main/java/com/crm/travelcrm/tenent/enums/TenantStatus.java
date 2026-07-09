package com.crm.travelcrm.tenent.enums;

/**
 * Tenant lifecycle. Operational states (staff can log in / operate) are {@code ACTIVE} and
 * {@code TRIAL}; {@code SUSPENDED} and {@code EXPIRED} block the tenant's users — see
 * {@code Tenant.isOperational()}.
 */
public enum TenantStatus {
    ACTIVE,
    TRIAL,
    SUSPENDED,
    EXPIRED,
    /**
     * Deprecated legacy value — retained so pre-existing rows still deserialize under
     * {@code ddl-auto=update}. Treated as non-operational everywhere. Do not assign to new tenants.
     */
    @Deprecated
    INACTIVE
}