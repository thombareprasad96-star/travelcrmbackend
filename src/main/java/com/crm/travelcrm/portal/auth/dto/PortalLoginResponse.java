package com.crm.travelcrm.portal.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** Issued on successful OTP verification — the traveler token plus minimal, safe profile info. */
@Data
@Builder
public class PortalLoginResponse {
    private String token;
    private long expiresInMs;
    private UUID customerPublicId;
    private String name;
}
