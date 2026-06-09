package com.crm.travelcrm.auth.dto;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String name;
    private String username;
    private String email;
    private String password;
    private String phoneNumber;
}
