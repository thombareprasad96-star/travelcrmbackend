package com.crm.travelcrm.auth.dto;

import com.crm.travelcrm.auth.enums.Role;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class UserResponseDTO {
    UUID   publicId;
    String name;
    /** Login identifier. Now the only field that identifies this user uniquely — email may repeat. */
    String username;
    String email;
    Role   role;
    String phoneNumber;
    Boolean isActive;
    LocalDateTime createdAt;
}