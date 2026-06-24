package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.EmailAlreadyExistsException;
import com.crm.travelcrm.common.staffip.StaffIpService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LogManager.getLogger(AuthServiceImpl.class);

    private final SuperAdminRepository superAdminRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StaffIpService staffIpService;

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
                superAdmin.getPublicId(),
                superAdmin.getEmail(),
                "SUPER_ADMIN"
        );
    }

    @Override
    public LoginResponseDTO userLogin(LoginRequestDTO request, String clientIp) {

        logger.trace("Entered userLogin()");
        logger.debug("Login request for email: {}", request.getEmail());

        // Soft-deleted users are never found — they cannot authenticate.
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElseThrow(() -> {
                    logger.warn("User not found: {}", request.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Password mismatch for user: {}", request.getEmail());
            throw new BadCredentialsException("Invalid email or password");
        }

        // Deactivated accounts cannot log in. Checked only after a correct password so
        // we don't reveal account state to someone who doesn't already hold the credentials.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            logger.warn("Login blocked for inactive user: {}", request.getEmail());
            throw new BusinessException(
                    "Your account is inactive. Please contact your administrator.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user);
        logger.info("User logged in: {}", request.getEmail());

        // Capture the staff member's IP into the tenant's "home IP" set — best-effort, never blocks login.
        staffIpService.recordStaffIp(user.getTenantId(), clientIp);

        return new LoginResponseDTO(
                user.getName(),
                "Login successful",
                token,
                "Bearer",
                user.getPublicId(),
                user.getEmail(),
                user.getRole().name()
        );
    }

    // ----------------------------- change password ----------------------------
    @Override
    @Transactional
    public void changePassword(User currentUser, String currentPassword, String newPassword) {

        logger.trace("Entered changePassword()");

        // Re-load a managed, non-deleted entity from the authenticated principal's email
        // (the principal can be detached, and soft-deleted users must never proceed).
        User user = userRepository.findByEmailAndDeletedAtIsNull(currentUser.getEmail())
                .orElseThrow(() -> {
                    logger.warn("Change-password requested for unknown user: {}", currentUser.getEmail());
                    return new BadCredentialsException("Invalid email or password");
                });

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            logger.warn("Change-password rejected — wrong current password for {}", user.getEmail());
            throw new BusinessException("Current password is incorrect.", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(
                    "New password must be different from the current password.",
                    HttpStatus.BAD_REQUEST);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        logger.info("Password changed for user: {}", user.getEmail());
    }
}