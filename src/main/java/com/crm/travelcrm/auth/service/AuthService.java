package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import org.springframework.http.ResponseEntity;

public interface AuthService {
    ResponseEntity<String> registerSuperAdmin(RegisterRequestDTO request);
    LoginResponseDTO superAdminLogin(LoginRequestDTO request);
    LoginResponseDTO userLogin(LoginRequestDTO request, String clientIp);
}