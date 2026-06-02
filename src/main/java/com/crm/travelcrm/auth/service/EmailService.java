package com.crm.travelcrm.auth.service;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger logger = LogManager.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    public void sendOtpEmail(String toEmail, String otp) {

        logger.trace("Entered sendOtpEmail()");
        logger.debug("Preparing OTP email for: {}", toEmail);

        SimpleMailMessage message = new SimpleMailMessage();

        message.setTo(toEmail);
        logger.debug("Email recipient set: {}", toEmail);

        message.setSubject("Your OTP for Password Reset");
        logger.debug("Email subject set for OTP mail");

        message.setText(
                "Hello,\n\n" +
                        "Your OTP for password reset is: " + otp + "\n" +
                        "Valid for 15 minutes.\n\n" +
                        "Do not share this OTP with anyone.\n\n" +
                        "Thanks,\nTravel CRM Team"
        );

        logger.debug("Email body prepared for OTP (OTP not logged for security)");

        mailSender.send(message);

        logger.info("OTP email sent successfully to: {}", toEmail);
        logger.trace("Exiting sendOtpEmail()");
    }
}