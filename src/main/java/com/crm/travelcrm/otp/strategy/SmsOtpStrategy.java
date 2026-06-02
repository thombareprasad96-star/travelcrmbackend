package com.crm.travelcrm.otp.strategy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service("SMS")
public class SmsOtpStrategy implements OtpStrategy {

    private static final Logger logger = LogManager.getLogger(SmsOtpStrategy.class);

    @Override
    public void sendOtp(String phone, String otp) {

        logger.info("Sending SMS OTP to {}", phone);
        logger.debug("OTP: {}", otp);

        logger.info("SMS OTP sent successfully to {}", phone);
    }
}