package com.crm.travelcrm.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SuperAdminInviteAcceptRequest {

    @NotBlank(message = "Password is required")
    @Size(min = 12, max = 128, message = "Password must be 12-128 characters")
    @Pattern(
            regexp = "^(?=\\S+$)(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
            message = "Password must contain uppercase, lowercase, number, and symbol"
    )
    private String password;

    @NotBlank(message = "MFA code is required")
    @Pattern(regexp = "^\\d{6}$", message = "MFA code must be 6 digits")
    private String code;
}
