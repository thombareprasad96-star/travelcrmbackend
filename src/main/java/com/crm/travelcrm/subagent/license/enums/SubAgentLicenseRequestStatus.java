package com.crm.travelcrm.subagent.license.enums;

/**
 * Lifecycle of a tenant-initiated sub-agent (Travel Partner) SEAT-LICENSE purchase request.
 *
 * <ul>
 *   <li>{@code PENDING} — submitted, awaiting SuperAdmin review (payment settled online, or
 *       asserted-but-unverified for offline). The purchased sub-agent(s) sit in {@code PENDING_LICENSE}.</li>
 *   <li>{@code APPROVED} — SuperAdmin approved; the seat cap was raised and the pending sub-agent(s)
 *       activated. Terminal.</li>
 *   <li>{@code REJECTED} — SuperAdmin declined; the sub-agent(s) stay {@code PENDING_LICENSE} with no
 *       portal access, and the tenant may resubmit payment. Terminal.</li>
 *   <li>{@code CANCELLED} — the tenant withdrew the request before review (the pending sub-agent is
 *       removed). Terminal.</li>
 * </ul>
 *
 * <p>Stored as {@code STRING}. New table ⇒ its {@code sub_agent_license_requests_status_check}
 * constraint is refreshed in {@code db/indexes.sql} so a future value is never rejected.
 */
public enum SubAgentLicenseRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}