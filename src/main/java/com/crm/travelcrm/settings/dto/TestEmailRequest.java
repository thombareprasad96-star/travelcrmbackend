package com.crm.travelcrm.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TestEmailRequest {

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid recipient email address")
    private String recipientEmail;
}