package com.crm.travelcrm.auth.enums;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public enum Role {
    SUPERADMIN,        // platform owner (no tenant)
    TENANT_ADMIN,      // "Organization Admin" — full control of one tenant
    MANAGER,
    TRAVEL_AGENT,
    STAFF,
    ACCOUNTANT,        // "Account" — finance/invoices/payments
    // B2B franchise partner: an intra-tenant sub-actor. Shares the parent's tenant_id, sees ONLY
    // its own records (OWN scope, see ScopeResolver) and holds NO legacy CRM_FULL authority — it can
    // act only through the fine-grained keys in its saved/default permission map (Permission.defaultsFor).
    SUB_AGENT;

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
            // Fail-closed: NO CRM_FULL. A sub-agent gets ZERO coarse authority, so it cannot reach any
            // controller still gated on CRM_FULL — only modules migrated to fine-grained keys (Phase 2),
            // and only via the keys its permission map grants (added by EffectivePermissionResolver).
            case SUB_AGENT -> List.of();
        };
    }
}