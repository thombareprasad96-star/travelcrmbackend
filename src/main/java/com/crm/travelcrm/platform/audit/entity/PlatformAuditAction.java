package com.crm.travelcrm.platform.audit.entity;

/**
 * The kind of platform-level (SuperAdmin) action recorded in {@code platform_audit_logs}.
 *
 * <p>Distinct from {@code com.crm.travelcrm.activity.entity.ActivityAction}, which is the
 * <b>tenant-scoped</b> staff audit trail. Platform actions have no tenant (or span tenants),
 * so they live in their own platform-level log. Stored as {@code STRING}.
 *
 * <p>The full set is declared up-front so later phases only need to <i>call</i> the recorder;
 * Foundation wires the session actions (LOGIN / LOGIN_FAILED) only.
 */
public enum PlatformAuditAction {

    // ── Session (Foundation) ─────────────────────────────────────────────
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,

    // ── Tenant lifecycle (Phase: Tenant Management) ──────────────────────
    TENANT_CREATE,
    TENANT_UPDATE,
    TENANT_SUSPEND,
    TENANT_REACTIVATE,
    TENANT_SOFT_DELETE,
    TENANT_RESTORE,
    TENANT_HARD_DELETE,

    // ── Subscription / billing (Phase: Subscriptions) ────────────────────
    PLAN_ASSIGN,
    PLAN_CHANGE,
    PLAN_UPDATE,
    SUBSCRIPTION_EXPIRED,
    BILLING_ISSUE,
    BILLING_MARK_PAID,
    BILLING_MARK_UNPAID,

    // ── Cross-tenant user control + impersonation (Phase: User Control) ──
    IMPERSONATION_START,
    IMPERSONATION_END,
    USER_FORCE_RESET,
    USER_LOCK,
    USER_UNLOCK,

    // ── Feature flags / global config (Phase: Feature Flags) ─────────────
    FEATURE_FLAG_CHANGE,
    CONFIG_CHANGE,

    // ── Usage metering & quotas (Phase: Usage & Quotas) ──────────────────
    QUOTA_OVERRIDE,          // SuperAdmin manually changed a tenant's usage limits
    USAGE_LIMIT_EXCEEDED,    // a tenant crossed a hard usage limit (recorded by the alert job)

    // ── Support / Ops (Phases: Support, Danger Zone) ─────────────────────
    ANNOUNCEMENT_SEND,
    MAINTENANCE_TOGGLE,
    DATA_EXPORT
}