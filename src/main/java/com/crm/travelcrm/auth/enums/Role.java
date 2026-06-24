package com.crm.travelcrm.auth.enums;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public enum Role {
    SUPERADMIN,        // platform owner (no tenant)
    TENANT_ADMIN,      // "Organization Admin" — full control of one tenant
    MANAGER,
    TRAVEL_AGENT,
    STAFF,
    ACCOUNTANT;        // "Account" — finance/invoices/payments

    // Authority-based security: roles map to fine-grained authorities.
    // The operational tenant roles share CRM_FULL today; split any of them by
    // changing this mapping alone.
    public List<SimpleGrantedAuthority> authorities() {
        return switch (this) {
            case SUPERADMIN   -> List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN"));
            case TENANT_ADMIN -> List.of(
                    new SimpleGrantedAuthority("USER_CREATE"),
                    new SimpleGrantedAuthority("USER_READ"),
                    new SimpleGrantedAuthority("USER_UPDATE"),
                    new SimpleGrantedAuthority("USER_DELETE"),
                    new SimpleGrantedAuthority("CRM_FULL"));
            case MANAGER, TRAVEL_AGENT, STAFF, ACCOUNTANT ->
                    List.of(new SimpleGrantedAuthority("CRM_FULL"));
        };
    }
}