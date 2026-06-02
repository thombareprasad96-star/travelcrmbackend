package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.ForgotPasswordRequestDTO;
import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.OtpChannel;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.common.exception.BadRequestException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.otp.dto.OtpRequestDTO;
import com.crm.travelcrm.otp.service.OtpService;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{

    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final OtpService otpService;

    private static final String PENDING_PREFIX = "pending:registration:";
    private static final long PENDING_EXPIRY_MINUTES = 15;

    @Override
    public void storeRegistrationPending(RegisterRequestDTO request) {

        logger.trace("Entered storeRegistrationPending()");
        logger.debug("Request received for email: {}", request.getEmail());

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            logger.warn("Email already registered: {}", request.getEmail());
            throw new BadRequestException("Email already registered: " + request.getEmail());
        }

        try {
            logger.debug("Converting request to JSON for Redis storage");

            String json = objectMapper.writeValueAsString(request);

            redisTemplate.opsForValue().set(
                    PENDING_PREFIX + request.getEmail().toLowerCase(),
                    json,
                    Duration.ofMinutes(PENDING_EXPIRY_MINUTES)
            );

            logger.info("Pending registration stored in Redis for email: {}", request.getEmail());

        } catch (Exception e) {
            logger.error("Error storing pending registration for email: {}", request.getEmail(), e);
            throw new RuntimeException("Failed to store pending registration", e);
        }
    }

    @Override
    public void completeRegistration(String email) {

        logger.trace("Entered completeRegistration()");
        logger.debug("Completing registration for email: {}", email);

        String key = PENDING_PREFIX + email.toLowerCase();
        String json = redisTemplate.opsForValue().get(key);

        if (json == null) {
            logger.warn("No pending registration found for email: {}", email);
            throw new IllegalStateException(
                    "No pending registration found for: " + email
                            + ". It may have expired. Please restart registration."
            );
        }

        try {
            logger.debug("Parsing pending registration JSON for email: {}", email);

            RegisterRequestDTO request = objectMapper.readValue(json, RegisterRequestDTO.class);

            User user = User.builder()
                    .name(request.getName())
                    .email(request.getEmail())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(request.getRole() != null ? request.getRole() : Role.AGENT)
                    .build();

            userRepository.save(user);
            redisTemplate.delete(key);

            logger.info("User registration completed successfully: {}", email);

        } catch (Exception e) {
            logger.error("Error completing registration for email: {}", email, e);
            throw new RuntimeException("Failed to complete registration", e);
        }
    }

    @Override
    public String login(LoginRequestDTO request) {

        logger.trace("Entered login()");
        logger.debug("Login attempt for email: {}", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("Login failed - user not found: {}", request.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Login failed - invalid password: {}", request.getEmail());
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtUtil.generateToken(user);

        logger.info("Login successful for email: {}", request.getEmail());
        logger.trace("Exiting login()");

        return token;
    }

    @Override
    public void forgotPassword(ForgotPasswordRequestDTO request) {

        logger.trace("Entered forgotPassword()");

        if (request.getEmail() != null && !request.getEmail().isBlank()) {

            logger.debug("Forgot password request via email: {}", request.getEmail());

            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> {
                        logger.warn("User not found for email: {}", request.getEmail());
                        return new ResourceNotFoundException("User not found");
                    });

            String otp = otpService.sendOtp(
                    new OtpRequestDTO(user.getEmail(), OtpChannel.EMAIL)
            );

            logger.debug("OTP generated for email (not logging OTP for security)");

            user.setOtp(otp);
            user.setOtpExpiry(otpService.getOtpExpiry(15));
            userRepository.save(user);

            logger.info("OTP sent for forgot password via email: {}", request.getEmail());
            return;
        }

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {

            logger.debug("Forgot password request via phone: {}", request.getPhoneNumber());

            User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                    .orElseThrow(() -> {
                        logger.warn("User not found for phone: {}", request.getPhoneNumber());
                        return new ResourceNotFoundException("User not found");
                    });

            String otp = otpService.sendOtp(
                    new OtpRequestDTO(user.getPhoneNumber(), OtpChannel.WHATSAPP)
            );

            logger.debug("OTP generated for phone (not logging OTP for security)");

            user.setOtp(otp);
            user.setOtpExpiry(otpService.getOtpExpiry(5));
            userRepository.save(user);

            logger.info("OTP sent for forgot password via phone: {}", request.getPhoneNumber());
            return;
        }

        logger.warn("Forgot password request missing email/phone");
        throw new BadRequestException("Email or phone number is required");
    }
}