package com.crm.travelcrm.platform.subscription.upgrade.enums;

/**
 * Lifecycle of a tenant-initiated plan {@code UpgradeRequest}.
 *
 * <ul>
 *   <li>{@code PENDING} — submitted, awaiting SuperAdmin review (payment may already be settled for
 *       ONLINE, or asserted-but-unverified for OFFLINE).</li>
 *   <li>{@code APPROVED} — SuperAdmin approved; the plan has been activated
 *       ({@code TenantPlanApplier.applyPlan}). Terminal.</li>
 *   <li>{@code REJECTED} — SuperAdmin declined with a reason; the tenant stays on its current plan.
 *       Terminal.</li>
 *   <li>{@code CANCELLED} — the tenant withdrew the request before review. Terminal.</li>
 * </ul>
 *
 * <p>Stored as {@code STRING}. New table ⇒ its {@code upgrade_requests_status_check} constraint is
 * refreshed in {@code db/indexes.sql} so a future value is never rejected.
 */
public enum UpgradeRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}