package com.crm.travelcrm.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Self-service update of the caller's OWN profile. Only name + phone are editable (not email/role). */
@Data
public class UpdateMeProfileRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @Size(max = 20, message = "Phone number is too long")
    private String phoneNumber;
}