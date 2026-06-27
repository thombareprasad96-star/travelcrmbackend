package com.crm.travelcrm.portal.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Step 2 of portal login: the identifier plus the one-time passcode that was sent. */
@Data
public class OtpVerifyDto {
    @NotBlank(message = "Phone or email is required")
    private String identifier;

    @NotBlank(message = "Code is required")
    private String otp;
}
