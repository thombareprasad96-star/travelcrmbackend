package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import org.springframework.http.ResponseEntity;

public interface AuthService {
    ResponseEntity<String> registerSuperAdmin(RegisterRequestDTO request);
    LoginResponseDTO superAdminLogin(LoginRequestDTO request);
    LoginResponseDTO userLogin(LoginRequestDTO request, String clientIp);

    /**
     * Changes the authenticated tenant user's own password. Verifies the current
     * password before applying the new one; rejects an unchanged password.
     */
    void changePassword(User currentUser, String currentPassword, String newPassword);
}