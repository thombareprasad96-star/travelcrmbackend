package com.crm.travelcrm.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class UserDto {
    private UUID publicId;
    private String fullName;
    private String role;
    private String email;
}
