package com.crm.travelcrm.platform.impersonation.service;

import com.crm.travelcrm.platform.impersonation.dto.ImpersonationResponse;

import java.util.UUID;

/** Time-boxed, fully-audited "act as a tenant user" for the SuperAdmin. */
public interface ImpersonationService {

    /** Mint a short-lived impersonation token for the target user (audits IMPERSONATION_START). */
    ImpersonationResponse start(UUID userPublicId);

    /** Record the end of an impersonation session from its token (audits IMPERSONATION_END). */
    void end(String impersonationToken);
}