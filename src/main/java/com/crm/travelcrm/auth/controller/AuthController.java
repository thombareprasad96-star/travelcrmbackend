package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.*;
import com.crm.travelcrm.auth.enums.OtpChannel;
import com.crm.travelcrm.auth.service.AuthService;
import com.crm.travelcrm.otp.dto.OtpRequestDTO;
import com.crm.travelcrm.otp.service.OtpService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger logger = LogManager.getLogger(AuthController.class);

    private final AuthService authService;
    private final OtpService otpService;

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginRequestDTO request) {

        logger.trace("Entered login() method");
        logger.debug("Login request received for user: {}", request.getEmail());

        String response = authService.login(request);

        logger.info("User logged in successfully: {}", request.getEmail());
        logger.trace("Exiting login() method");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/register/initiate")
    public ResponseEntity<String> initiateRegistration(@RequestBody RegisterRequestDTO request) {

        logger.trace("Entered initiateRegistration()");
        logger.debug("Registration request received: {}", request);

        authService.storeRegistrationPending(request);
        logger.info("Registration data stored in Redis for email: {}", request.getEmail());

        OtpRequestDTO otpRequest = new OtpRequestDTO();
        otpRequest.setChannel(OtpChannel.EMAIL);
        otpRequest.setDestination(request.getEmail());

        logger.debug("OTP request prepared: channel={}, destination={}",
                otpRequest.getChannel(), otpRequest.getDestination());

        otpService.sendOtp(otpRequest);

        logger.info("OTP sent successfully to email: {}", request.getEmail());
        logger.trace("Exiting initiateRegistration()");

        return ResponseEntity.ok(
                "OTP sent to " + request.getEmail() + ". Please verify to complete registration."
        );
    }

    @PostMapping("/register/verify")
    public ResponseEntity<String> verifyAndRegister(
            @RequestParam String email,
            @RequestParam String otp
    ) {

        logger.trace("Entered verifyAndRegister()");
        logger.debug("OTP verification request for email: {}", email);

        OtpRequestDTO otpRequest = new OtpRequestDTO();
        otpRequest.setChannel(OtpChannel.EMAIL);
        otpRequest.setDestination(email);

        logger.debug("Verifying OTP for email: {}", email);

        otpService.verifyOtp(otpRequest, otp);

        logger.info("OTP verified successfully for email: {}", email);

        authService.completeRegistration(email);

        logger.info("User registration completed for email: {}", email);
        logger.trace("Exiting verifyAndRegister()");

        return ResponseEntity.ok("Registration successful! You can now log in.");
    }

    @PostMapping("/forgotpassword")
    public ResponseEntity<String> forgotPassword(
            @RequestBody ForgotPasswordRequestDTO request) {

        logger.trace("Entered forgotPassword()");
        logger.debug("Forgot password request for email: {}", request.getEmail());

        authService.forgotPassword(request);

        logger.info("Password reset process initiated for email: {}", request.getEmail());
        logger.trace("Exiting forgotPassword()");

        return ResponseEntity.ok("Reset password link sent");
    }
}