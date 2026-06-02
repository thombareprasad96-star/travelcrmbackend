package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.ForgotPasswordRequestDTO;
import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;


public interface AuthService {
    void storeRegistrationPending(RegisterRequestDTO request);
    void completeRegistration(String email);
    String login(LoginRequestDTO request);
    void forgotPassword(ForgotPasswordRequestDTO request);  // NEW
}