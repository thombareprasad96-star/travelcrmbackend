package com.crm.travelcrm.subagent.enums;

/**
 * Provisioning lifecycle of a sub-agent.
 *
 * <ul>
 *   <li>{@code PENDING_LICENSE} — created but NOT yet licensed: the tenant was over its seat cap, so a
 *       seat-license purchase ({@code SubAgentLicenseRequest}) must be paid and SuperAdmin-approved
 *       before the sub-agent activates. The login is disabled while pending (no portal access), and a
 *       pending sub-agent does NOT consume a licensed seat and is NOT billed.</li>
 *   <li>{@code ACTIVE} — licensed and operating; the login is enabled and the seat is billed monthly.</li>
 *   <li>{@code SUSPENDED} — deactivates the login but still holds a licensed seat.</li>
 * </ul>
 */
public enum SubAgentStatus {
    PENDING_LICENSE,
    ACTIVE,
    SUSPENDED
}