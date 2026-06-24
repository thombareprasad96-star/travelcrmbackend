package com.crm.travelcrm.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// Self-service password change for the authenticated tenant user.
// The full strength checklist (upper/lower/number/special) is enforced
// client-side; the backend keeps the minimal not-blank + min-length guard
// that matches the documented frontend contract.
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @Size(min = 6, message = "New password must be at least 6 characters")
    private String newPassword;
}