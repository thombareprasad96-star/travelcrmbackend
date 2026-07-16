package com.crm.travelcrm.auth.dto;

import lombok.Builder;
import lombok.Data;

/** The authenticated user's own account profile (self-service read view). */
@Data
@Builder
public class MeProfileResponse {

    private String name;
    /** Login identifier — read-only. */
    private String email;
    private String phoneNumber;
    /** Role name — read-only. */
    private String role;
}