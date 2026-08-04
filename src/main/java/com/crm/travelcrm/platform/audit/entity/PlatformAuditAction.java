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
    MFA_SETUP,
    MFA_CHALLENGE,
    MFA_VERIFY,
    MFA_VERIFY_FAILED,
    MFA_ENABLE_FAILED,
    MFA_ENABLED,
    MFA_DISABLE_FAILED,
    MFA_DISABLED,
    SUPER_ADMIN_MFA_RESET,
    SUPER_ADMIN_PASSWORD_CHANGE,
    SUPER_ADMIN_INVITE_CREATE,
    SUPER_ADMIN_INVITE_ACCEPT,
    LOGIN_NOTIFICATION,
    LOGIN_NOTIFICATION_FAILED,

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
    BILLING_VOID,            // an outstanding invoice was voided (e.g. a rejected/cancelled upgrade request)
    PAYMENT_ORDER_CREATED,   // tenant created a gateway order to pay an invoice online
    PAYMENT_CAPTURED,        // gateway confirmed a successful payment (webhook)
    PAYMENT_FAILED,          // gateway reported a failed payment attempt (webhook)
    SUBSCRIPTION_ACTIVATED,  // recurring subscription activated/charged (webhook; scaffold)
    SUBSCRIPTION_CANCELLED,  // recurring subscription cancelled/halted (webhook; scaffold)
    TENANT_PAST_DUE,         // tenant entered the dunning grace window (overdue invoice / failed payment)

    // ── Tenant-initiated plan upgrade requests (Phase: Upgrade Approval) ─────
    UPGRADE_REQUEST_CREATE,  // tenant submitted a plan-upgrade request (awaiting SuperAdmin approval)
    UPGRADE_REQUEST_APPROVE, // SuperAdmin approved a request → plan activated
    UPGRADE_REQUEST_REJECT,  // SuperAdmin rejected a request (tenant stays on current plan)
    UPGRADE_REQUEST_CANCEL,  // tenant withdrew a pending request

    // ── Sub-agent (Travel Partner) seat-license requests (Phase: Broker Licensing) ─────
    SUBAGENT_LICENSE_CREATE,  // tenant purchased sub-agent seat(s) (awaiting SuperAdmin approval)
    SUBAGENT_LICENSE_APPROVE, // SuperAdmin approved → seats granted, pending sub-agent(s) activated
    SUBAGENT_LICENSE_REJECT,  // SuperAdmin rejected (sub-agent stays PENDING_LICENSE; may resubmit)
    SUBAGENT_LICENSE_CANCEL,  // tenant withdrew a pending seat-license request

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
    DATA_EXPORT,

    // ── Hotel marketplace catalog (Phase: Marketplace catalog + sync) ────
    // Catalog rows are global and every tenant can see a published one, so publish/unpublish are
    // recorded separately from ordinary edits: they are the two actions that change what the whole
    // customer base can buy.
    PLATFORM_HOTEL_CREATE,
    PLATFORM_HOTEL_UPDATE,
    PLATFORM_HOTEL_PUBLISH,
    PLATFORM_HOTEL_UNPUBLISH,
    PLATFORM_HOTEL_DELETE,

    // ── Hotel marketplace booking requests ───────────────────────────────
    MARKETPLACE_BOOKING_REQUEST,          // tenant submitted a hotel booking request
    MARKETPLACE_BOOKING_REVIEW,           // SuperAdmin took it under review
    MARKETPLACE_BOOKING_APPROVE,          // confirmed with the supplier; CRM projection follows
    MARKETPLACE_BOOKING_REJECT,
    MARKETPLACE_BOOKING_CANCEL,
    // The CRM projection could not be written after a CONFIRMED approval. Audited because the
    // platform and the tenant's CRM now disagree until the scheduler drains it.
    MARKETPLACE_BOOKING_CRM_SYNC_FAILED,

    // ── Price revision (design §8 Step 6B) ───────────────────────────────
    // Recorded as three distinct actions rather than one "revision" with a payload: who moved a
    // price, and whether the tenant agreed, is the sequence an audit of a repricing has to answer.
    MARKETPLACE_BOOKING_REVISION_REQUESTED,
    MARKETPLACE_BOOKING_REVISION_ACCEPTED,
    MARKETPLACE_BOOKING_REVISION_DECLINED,
    MARKETPLACE_BOOKING_REVISION_EXPIRED,

    // ── Cancellation ─────────────────────────────────────────────────────
    MARKETPLACE_BOOKING_CANCEL_REQUESTED,   // tenant asked; the supplier conversation starts here
    // A charge the tenant will be billed for is put to them, and answered, before it binds — the
    // same consent trail the price revision keeps.
    MARKETPLACE_CANCELLATION_QUOTED,
    MARKETPLACE_CANCELLATION_QUOTE_ACCEPTED,
    MARKETPLACE_CANCELLATION_QUOTE_DECLINED,
    MARKETPLACE_CANCELLATION_QUOTE_EXPIRED,

    // ── Voucher (design §7) ──────────────────────────────────────────────
    MARKETPLACE_VOUCHER_ISSUED,
    MARKETPLACE_VOUCHER_REVOKED,

    // ── Platform earning ledger (design §5.12) ───────────────────────────
    // Append-only, so every row that lands in it is also an audited act: the ledger is the source
    // for platform revenue reporting and must be reconstructable from the audit trail alone.
    MARKETPLACE_COMMISSION_ACCRUED,
    MARKETPLACE_COMMISSION_REVERSED,
    MARKETPLACE_COMMISSION_ADJUSTED,
    MARKETPLACE_COMMISSION_SETTLED
}
