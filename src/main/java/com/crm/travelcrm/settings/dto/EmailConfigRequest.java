package com.crm.travelcrm.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailConfigRequest {

    @NotBlank(message = "SMTP Host is required")
    private String smtpHost;

    private int portNumber;                 // 587 | 465 | 25 | 2525

    private String encryption;              // TLS | SSL | None

    @NotBlank(message = "Username is required")
    private String username;

    private boolean passwordChanged;        // true = a new password is included

    private String password;                // present only when passwordChanged=true

    @NotBlank(message = "From Email is required")
    @Email(message = "Invalid email format for From Email")
    private String fromEmail;

    @NotBlank(message = "From Name is required")
    private String fromName;
}