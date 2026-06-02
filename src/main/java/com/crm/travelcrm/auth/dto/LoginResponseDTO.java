package com.crm.travelcrm.auth.dto;

import com.crm.travelcrm.auth.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponseDTO {

    private String message;

    private String token;

    private String tokenType;

    private Long userId;

    private String email;

    private Role role;
}