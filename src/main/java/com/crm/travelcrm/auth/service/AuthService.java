package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.SuperAdminMfaVerifyRequest;
import com.crm.travelcrm.auth.entity.User;

public interface AuthService {
    LoginResponseDTO superAdminLogin(LoginRequestDTO request, String clientIp, String userAgent);
    LoginResponseDTO verifySuperAdminMfa(SuperAdminMfaVerifyRequest request, String clientIp, String userAgent);
    LoginResponseDTO userLogin(LoginRequestDTO request, String clientIp);

    /**
     * Changes the authenticated tenant user's own password. Verifies the current
     * password before applying the new one; rejects an unchanged password.
     */
    void changePassword(User currentUser, String currentPassword, String newPassword);
}
