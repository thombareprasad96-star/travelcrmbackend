package com.crm.travelcrm.otp.strategy;

public interface OtpStrategy {
    void sendOtp(String destination, String otp);
}