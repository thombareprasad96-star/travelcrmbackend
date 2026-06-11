package com.crm.travelcrm.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class LoginResponseDTO {
    private String name;
    private String message;
    private String token;
    private String tokenType;
    private UUID   id;
    private String email;
    private String role;
}