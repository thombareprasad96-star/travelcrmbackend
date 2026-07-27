package com.crm.travelcrm.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SuperAdminMfaVerifyRequest {

    @NotBlank(message = "MFA challenge is required")
    private String challengeId;

    @NotBlank(message = "MFA code is required")
    @Pattern(regexp = "^\\d{6}$", message = "MFA code must be 6 digits")
    private String code;
}
