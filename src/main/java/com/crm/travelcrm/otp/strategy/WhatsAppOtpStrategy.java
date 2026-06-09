package com.crm.travelcrm.otp.strategy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service("WHATSAPP")
public class WhatsAppOtpStrategy implements OtpStrategy {

    private static final Logger logger = LogManager.getLogger(WhatsAppOtpStrategy.class);

    @Override
    public void sendOtp(String phone, String otp) {

        logger.info("Sending WHATSAPP OTP to {}", phone);
        logger.debug("OTP: {}", otp);

        // WhatsApp API integration here

        logger.info("WHATSAPP OTP sent successfully to {}", phone);
    }
}