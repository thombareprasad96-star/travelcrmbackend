package com.crm.travelcrm.platform.impersonation.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The result of starting an impersonation session: a short-lived token that authenticates as the
 * target user, plus display info for the impersonation banner. The token is never persisted.
 */
@Data
@Builder
public class ImpersonationResponse {
    private String token;
    private long expiresInMs;
    private String name;
    private String email;
    private String role;
    private String tenantCode;
    private String tenantName;
}