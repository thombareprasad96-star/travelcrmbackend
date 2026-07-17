package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.activity.audit.ActivityLogRecorder;
import com.crm.travelcrm.activity.entity.ActivityAction;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.EmailAlreadyExistsException;
import com.crm.travelcrm.common.staffip.StaffIpService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
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
    private final ActivityLogRecorder activityLogRecorder;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final TenantRepository tenantRepository;

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
    public LoginResponseDTO superAdminLogin(LoginRequestDTO request, String clientIp, String userAgent) {

        logger.trace("Entered superAdminLogin()");
        logger.debug("Login request for email: {}", request.getEmail());

        // H1 — soft-deleted platform accounts are never found, so they can never authenticate
        // (mirrors userLogin's findByEmailAndDeletedAtIsNull).
        SuperAdmin superAdmin = superAdminRepository
                .findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElse(null);

        // Invalid email or wrong password are reported identically (no account-enumeration),
        // but both are recorded to the platform audit trail (best-effort, never blocks).
        if (superAdmin == null
                || !passwordEncoder.matches(request.getPassword(), superAdmin.getPassword())) {
            logger.warn("SuperAdmin login failed: {}", request.getEmail());
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin != null ? superAdmin.getId() : null, request.getEmail(),
                    null, null, null, null,
                    "Invalid credentials", clientIp, userAgent);
            throw new BadCredentialsException("Invalid email or password");
        }

        // Disabled platform account cannot log in (checked only after a correct password).
        if (!superAdmin.isEnabled()) {
            logger.warn("SuperAdmin login blocked (disabled): {}", request.getEmail());
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, null, null,
                    "Account disabled", clientIp, userAgent);
            throw new BusinessException(
                    "Your account is disabled. Please contact platform support.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(superAdmin);
        logger.info("SuperAdmin logged in: {}", request.getEmail());

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.LOGIN, true,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, null, null,
                "SuperAdmin login", clientIp, userAgent);

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

        // Email alone identifies exactly one account platform-wide (uq_users_email_active), so this
        // needs no tenant discriminator — the tenant is a RESULT of the lookup, not an input to it.
        // Under the old per-tenant constraint this same line matched two rows and threw
        // NonUniqueResultException: an unauthenticated 500, fired before the password check.
        //
        // Normalized to match how every writer stores it; the index is case-sensitive and all
        // stored addresses are lowercase, so an un-normalized lookup would just miss.
        // Soft-deleted users are never found — they cannot authenticate.
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> {
                    logger.warn("User not found: {}", email);
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

        // Tenant lifecycle gate — the teeth behind a SuperAdmin suspend/expire/soft-delete: a
        // non-operational organization blocks ALL of its staff from logging in. Checked after a
        // correct password so tenant state is never revealed to a non-credential-holder.
        Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
        if (tenant == null || !tenant.isOperational()) {
            logger.warn("Login blocked — organization not operational for user: {}", request.getEmail());
            throw new BusinessException(
                    "Your organization's account is not active. Please contact support.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user);
        logger.info("User logged in: {}", request.getEmail());

        // Capture the staff member's IP into the tenant's "home IP" set — best-effort, never blocks login.
        staffIpService.recordStaffIp(user.getTenantId(), clientIp);

        // Audit trail — best-effort; a logging failure must never block a valid login.
        activityLogRecorder.safeRecord(
                ActivityAction.Login,
                "User logged in from IP: " + clientIp,
                user.getId(), user.getName(), user.getEmail(),
                ActivityLogRecorder.labelFor(user.getRole()),
                user.getTenantId(), clientIp, null);

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

        // Re-load a managed, non-deleted entity (the principal can be detached, and soft-deleted
        // users must never proceed). Keyed on the principal's id + tenantId, NOT its email: the
        // principal already carries the exact identity, so re-resolving by a weaker key can only
        // lose information. Writing a password hash is the highest-consequence operation here — an
        // email-keyed lookup that ever resolved to the wrong row would be an account takeover.
        User user = userRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(currentUser.getId(), currentUser.getTenantId())
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