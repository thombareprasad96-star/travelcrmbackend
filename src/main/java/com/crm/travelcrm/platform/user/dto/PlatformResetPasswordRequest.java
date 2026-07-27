package com.crm.travelcrm.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** SuperAdmin sets a new password for a tenant user (no current-password check). */
@Data
public class PlatformResetPasswordRequest {

    @NotBlank(message = "New password is required")
    @Size(min = 12, max = 128, message = "Password must be 12-128 characters")
    @Pattern(
            regexp = "^(?=\\S+$)(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$",
            message = "Password must contain uppercase, lowercase, number, and symbol"
    )
    private String newPassword;
}
