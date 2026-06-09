package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BadRequestException;
import com.crm.travelcrm.common.exception.EmailAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);

    private final SuperAdminRepository superAdminRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    // ------------------------------------------------------------------ register

    @Override
    public ResponseEntity<String> registerSuperAdmin(RegisterRequestDTO request) {

        logger.trace("Entered registerSuperAdmin()");
        logger.debug("Registration request for email: {}", request.getEmail());

        if (superAdminRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        // check 2 — only one superadmin allowed
        if (superAdminRepository.count() > 0) {
            logger.warn("Attempt to create duplicate SuperAdmin blocked");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("SuperAdmin already exists");
        }

        SuperAdmin superAdmin = SuperAdmin.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        superAdminRepository.save(superAdmin);
        logger.info("SuperAdmin registered successfully: {}", request.getEmail());
        return ResponseEntity.ok("SuperAdmin registered successfully");
    }

    @Override
    public void registerUser(RegisterRequestDTO request) {

        logger.trace("Entered registerUser()");
        logger.debug("Registration request for email: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            logger.warn("Email already registered: {}", request.getEmail());
            throw new BadRequestException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        userRepository.save(user);
        logger.info("User registered successfully: {}", request.getEmail());
    }
    // ----------------------------- login---------------------------------
    @Override
    public LoginResponseDTO superAdminLogin(LoginRequestDTO request) {

        logger.trace("Entered superAdminLogin()");
        logger.debug("Login request for email: {}", request.getEmail());

        SuperAdmin superAdmin = superAdminRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("SuperAdmin not found: {}", request.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), superAdmin.getPassword())) {
            logger.warn("Password mismatch for SuperAdmin: {}", request.getEmail());
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtUtil.generateToken(superAdmin);
        logger.info("SuperAdmin logged in: {}", request.getEmail());

        return new LoginResponseDTO(
                superAdmin.getName(),
                "Login successful",
                token,
                "Bearer",
                superAdmin.getId(),
                superAdmin.getEmail(),
                "SUPER_ADMIN"
        );
    }

    @Override
    public LoginResponseDTO userLogin(LoginRequestDTO request) {

        logger.trace("Entered userLogin()");
        logger.debug("Login request for email: {}", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("User not found: {}", request.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Password mismatch for user: {}", request.getEmail());
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtUtil.generateToken(user);
        logger.info("User logged in: {}", request.getEmail());

        return new LoginResponseDTO(
                user.getName(),
                "Login successful",
                token,
                "Bearer",
                user.getId(),
                user.getEmail(),
                user.getRole().name()
        );
    }
}