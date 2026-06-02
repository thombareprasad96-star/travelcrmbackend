package com.crm.travelcrm.otp.strategy;

import com.crm.travelcrm.auth.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service("EMAIL")
@RequiredArgsConstructor
public class EmailOtpStrategy implements OtpStrategy {

    private static final Logger logger = LogManager.getLogger(EmailOtpStrategy.class);

    private final EmailService emailService;

    @Override
    public void sendOtp(String email, String otp) {

        logger.info("Preparing EMAIL OTP for {}", email);

        emailService.sendOtpEmail(email, otp);

        logger.info("EMAIL OTP successfully sent to {}", email);
    }
}