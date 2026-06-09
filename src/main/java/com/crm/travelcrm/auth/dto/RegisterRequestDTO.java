package com.crm.travelcrm.auth.dto;

import com.crm.travelcrm.auth.enums.Role;
import lombok.Data;

@Data
public class RegisterRequestDTO {
    private String name;
    private String email;
    private String password;
}