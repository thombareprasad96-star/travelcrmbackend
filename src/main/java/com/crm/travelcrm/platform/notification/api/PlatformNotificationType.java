package com.crm.travelcrm.platform.notification.api;

/**
 * Type and reference-type constants for platform notifications.
 *
 * <p>Constants on a final class rather than an enum, matching how {@code Notification.type} is
 * already treated (a free-form {@code VARCHAR}). Publishers get autocomplete and a single place to
 * read the vocabulary; the storage layer stays additive, so a new event type never needs a schema
 * change or a {@code *_check} constraint refresh.
 */
public final class PlatformNotificationType {

    private PlatformNotificationType() {}

    // ── Revenue ──────────────────────────────────────────────────────────────────────────────
    public static final String SAAS_PAYMENT_RECEIVED  = "SAAS_PAYMENT_RECEIVED";
    public static final String SAAS_PAYMENT_FAILED    = "SAAS_PAYMENT_FAILED";
    public static final String PLAN_DOWNGRADED        = "PLAN_DOWNGRADED";

    // ── Approval queue (a tenant is blocked until the SuperAdmin acts) ────────────────────────
    public static final String UPGRADE_REQUEST_CREATED        = "UPGRADE_REQUEST_CREATED";
    public static final String SUBAGENT_LICENSE_REQUEST_CREATED = "SUBAGENT_LICENSE_REQUEST_CREATED";

    /** A tenant wants a hotel booked. Nothing moves until a SuperAdmin works it with the supplier. */
    public static final String MARKETPLACE_BOOKING_REQUESTED  = "MARKETPLACE_BOOKING_REQUESTED";

    /** A tenant wants out of a booking already committed to a supplier — always a human decision. */
    public static final String MARKETPLACE_CANCEL_REQUESTED   = "MARKETPLACE_CANCEL_REQUESTED";

    // ── Health ───────────────────────────────────────────────────────────────────────────────
    public static final String USAGE_LIMIT_EXCEEDED   = "USAGE_LIMIT_EXCEEDED";
    public static final String TENANT_PAST_DUE        = "TENANT_PAST_DUE";
    public static final String TENANT_SUSPENDED       = "TENANT_SUSPENDED";

    // ── Reference types — the console's deep-link discriminator ───────────────────────────────
    public static final String REF_BILLING            = "BILLING";
    public static final String REF_TENANT             = "TENANT";
    public static final String REF_UPGRADE_REQUEST    = "UPGRADE_REQUEST";
    public static final String REF_SUBAGENT_LICENSE   = "SUBAGENT_LICENSE";
    public static final String REF_SUBSCRIPTION       = "SUBSCRIPTION";
    public static final String REF_MARKETPLACE_BOOKING = "MARKETPLACE_BOOKING";
}