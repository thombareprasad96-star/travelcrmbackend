package com.crm.travelcrm.auth.dto;

import com.crm.travelcrm.auth.enums.Role;
import jakarta.validation.constraints.Size;
import lombok.Data;

// All fields optional — only non-null values are applied (partial update).
// Email and password changes are intentionally excluded; they need their own flows.
@Data
public class UpdateUserRequest {

    @Size(max = 150, message = "Name must be 150 characters or fewer")
    private String name;

    private Role role;

    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phoneNumber;

    private Boolean isActive;
}