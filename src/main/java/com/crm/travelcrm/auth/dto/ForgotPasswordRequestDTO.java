package com.crm.travelcrm.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ForgotPasswordRequestDTO {

    // Email (Optional)
    @Email(message = "Invalid email format")
    private String email;

    // Indian Mobile Number (Optional)
    @Pattern(
            regexp = "^[6-9]\\d{9}$",
            message = "Invalid Indian mobile number"
    )
    private String phoneNumber;
}