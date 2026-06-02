package com.crm.travelcrm.otp.service;


import com.crm.travelcrm.otp.dto.OtpRequestDTO;

import java.time.LocalDateTime;

public interface OtpService {
    String sendOtp(OtpRequestDTO request);
    LocalDateTime getOtpExpiry(int minutes);
    boolean verifyOtp(OtpRequestDTO request, String otp);
    String generateOtp();
}