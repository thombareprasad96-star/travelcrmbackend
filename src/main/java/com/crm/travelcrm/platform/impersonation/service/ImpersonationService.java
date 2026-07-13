package com.crm.travelcrm.platform.impersonation.service;

import com.crm.travelcrm.platform.impersonation.dto.ImpersonationResponse;

import java.util.UUID;

/** Time-boxed, fully-audited "act as a tenant user" for the SuperAdmin. */
public interface ImpersonationService {

    /**
     * Mint a short-lived impersonation token for the target user (audits IMPERSONATION_START).
     * {@code ipAddress}/{@code userAgent} are the acting SuperAdmin's request origin — recorded
     * on the audit row ("from where") so an impersonation can be traced to a device/session.
     */
    ImpersonationResponse start(UUID userPublicId, String ipAddress, String userAgent);

    /**
     * Record the end of an impersonation session from its token (audits IMPERSONATION_END).
     * {@code ipAddress}/{@code userAgent} identify the origin that closed the session.
     */
    void end(String impersonationToken, String ipAddress, String userAgent);
}