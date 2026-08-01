package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.SuperAdminMfaVerifyRequest;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.mfa.SuperAdminMfaChallenge;
import com.crm.travelcrm.auth.mfa.SuperAdminMfaChallengeStore;
import com.crm.travelcrm.auth.mfa.SuperAdminMfaService;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.auth.security.SuperAdminPasswordPolicy;
import com.crm.travelcrm.auth.util.UsernamePolicy;
import com.crm.travelcrm.activity.audit.ActivityLogRecorder;
import com.crm.travelcrm.activity.entity.ActivityAction;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.staffip.StaffIpService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

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
    private final SuperAdminMfaChallengeStore mfaChallengeStore;
    private final SuperAdminMfaService mfaService;
    private final SuperAdminPasswordPolicy superAdminPasswordPolicy;
    private final SuperAdminLoginNotificationService superAdminLoginNotificationService;

    @Value("${app.super-admin.lockout.max-failed-attempts:5}")
    private int superAdminLockoutMaxAttempts;

    @Value("${app.super-admin.lockout.duration-seconds:900}")
    private long superAdminLockoutDurationSeconds;

    // ----------------------------- login---------------------------------
    @Override
    @Transactional
    public LoginResponseDTO superAdminLogin(LoginRequestDTO request, String clientIp, String userAgent) {

        logger.trace("Entered superAdminLogin()");
        logger.debug("Login request for email: {}", request.getEmail());
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase(Locale.ROOT);

        // H1 — soft-deleted platform accounts are never found, so they can never authenticate
        // (mirrors userLogin's findByEmailAndDeletedAtIsNull).
        SuperAdmin superAdmin = superAdminRepository
                .findByEmailAndDeletedAtIsNull(email)
                .orElse(null);

        if (superAdmin != null && isSuperAdminLoginLocked(superAdmin)) {
            logger.warn("SuperAdmin login blocked by temporary lockout: {}", email);
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, null, null,
                    "Account temporarily locked after repeated failed login attempts", clientIp, userAgent);
            throw new BusinessException(
                    "Too many failed login attempts. Please try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // Invalid email or wrong password are reported identically (no account-enumeration),
        // but both are recorded to the platform audit trail (best-effort, never blocks).
        if (superAdmin == null
                || !passwordEncoder.matches(request.getPassword(), superAdmin.getPassword())) {
            if (superAdmin != null) {
                recordFailedSuperAdminPassword(superAdmin);
            }
            logger.warn("SuperAdmin login failed: {}", email);
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin != null ? superAdmin.getId() : null, email,
                    null, null, null, null,
                    "Invalid credentials", clientIp, userAgent);
            throw new BadCredentialsException("Invalid email or password");
        }

        // Disabled platform account cannot log in (checked only after a correct password).
        if (!superAdmin.isEnabled()) {
            logger.warn("SuperAdmin login blocked (disabled): {}", email);
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, null, null,
                    "Account disabled", clientIp, userAgent);
            throw new BusinessException(
                    "Your account is disabled. Please contact platform support.",
                    HttpStatus.FORBIDDEN);
        }

        resetSuperAdminPasswordFailures(superAdmin);

        return issueSuperAdminMfaChallenge(superAdmin, clientIp, userAgent);
    }

    @Override
    @Transactional
    public LoginResponseDTO verifySuperAdminMfa(
            SuperAdminMfaVerifyRequest request, String clientIp, String userAgent) {

        SuperAdminMfaChallenge challenge = mfaChallengeStore.find(request.getChallengeId())
                .orElseThrow(() -> new BusinessException(
                        "MFA challenge has expired. Please sign in again.",
                        HttpStatus.UNAUTHORIZED));

        SuperAdmin superAdmin = superAdminRepository
                .findByIdAndDeletedAtIsNull(challenge.superAdminId())
                .orElseThrow(() -> new BusinessException(
                        "MFA challenge has expired. Please sign in again.",
                        HttpStatus.UNAUTHORIZED));

        if (!superAdmin.isEnabled()) {
            mfaChallengeStore.consume(request.getChallengeId());
            auditSuperAdminMfaFailure(superAdmin, "MFA rejected because the account is disabled",
                    clientIp, userAgent);
            throw new BusinessException(
                    "MFA challenge has expired. Please sign in again.",
                    HttpStatus.UNAUTHORIZED);
        }

        if (!mfaService.verifyCode(superAdmin, request.getCode())) {
            boolean stillOpen = mfaChallengeStore.recordFailure(request.getChallengeId());
            auditSuperAdminMfaFailure(superAdmin, "Invalid SuperAdmin MFA code", clientIp, userAgent);
            throw new BusinessException(
                    stillOpen ? "Invalid MFA code." : "Too many invalid MFA attempts. Please sign in again.",
                    HttpStatus.UNAUTHORIZED);
        }

        mfaChallengeStore.consume(request.getChallengeId());

        LocalDateTime now = LocalDateTime.now();
        boolean enabledDuringThisRequest = false;
        if (!superAdmin.isMfaEnabled()) {
            superAdmin.setMfaEnabled(true);
            superAdmin.setMfaEnabledAt(now);
            superAdmin.bumpTokenVersion();
            enabledDuringThisRequest = true;
        }
        superAdmin.setMfaLastUsedAt(now);

        boolean newDeviceOrIp = superAdminLoginNotificationService
                .isNewDeviceOrIp(superAdmin, clientIp, userAgent);
        superAdmin.setLastLoginAt(now);
        superAdmin.setLastLoginIp(clientIp);
        superAdmin.setLastLoginUserAgentHash(
                superAdminLoginNotificationService.userAgentHash(userAgent));
        superAdminRepository.save(superAdmin);

        if (enabledDuringThisRequest) {
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.MFA_ENABLED, true,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                    "Mandatory SuperAdmin MFA enabled", clientIp, userAgent);
        }

        String token = jwtUtil.generateToken(superAdmin);
        logger.info("SuperAdmin MFA verified: {}", superAdmin.getEmail());

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_VERIFY, true,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                "SuperAdmin MFA verified", clientIp, userAgent);
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.LOGIN, true,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, null, null,
                "SuperAdmin login after MFA", clientIp, userAgent);

        superAdminLoginNotificationService.notifyLogin(
                superAdmin, clientIp, userAgent, newDeviceOrIp, now);

        LoginResponseDTO response = new LoginResponseDTO(
                superAdmin.getName(),
                "Login successful",
                token,
                "Bearer",
                superAdmin.getPublicId(),
                superAdmin.getEmail(),
                "SUPER_ADMIN");
        response.setMustChangePassword(superAdmin.isMustChangePassword());
        response.setSetupRequired(!superAdmin.isSetupComplete());
        return response;
    }

    private LoginResponseDTO issueSuperAdminMfaChallenge(
            SuperAdmin superAdmin, String clientIp, String userAgent) {

        if (!superAdmin.isMfaEnabled()) {
            String secret = mfaService.ensureSetupSecret(superAdmin.getId(), clientIp, userAgent);
            SuperAdminMfaChallengeStore.CreatedChallenge challenge =
                    mfaChallengeStore.create(superAdmin, clientIp, userAgent);
            logger.info("Mandatory SuperAdmin MFA setup challenge issued: {}", superAdmin.getEmail());
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.MFA_CHALLENGE, true,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                    "Mandatory SuperAdmin MFA setup challenge issued", clientIp, userAgent);
            return LoginResponseDTO.mfaSetupRequired(
                    superAdmin.getName(),
                    superAdmin.getEmail(),
                    superAdmin.getPublicId(),
                    challenge.id(),
                    challenge.expiresInSeconds(),
                    mfaService.issuer(),
                    secret,
                    mfaService.otpAuthUri(superAdmin.getEmail(), secret));
        }

        if (superAdmin.getMfaSecretEnc() == null || superAdmin.getMfaSecretEnc().isBlank()) {
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.LOGIN_FAILED, false,
                    superAdmin.getId(), superAdmin.getEmail(),
                    null, null, null, null,
                    "MFA enabled without a configured secret", clientIp, userAgent);
            throw new BusinessException(
                    "MFA is not configured correctly. Please contact platform support.",
                    HttpStatus.FORBIDDEN);
        }

        SuperAdminMfaChallengeStore.CreatedChallenge challenge =
                mfaChallengeStore.create(superAdmin, clientIp, userAgent);
        logger.info("SuperAdmin MFA challenge issued: {}", superAdmin.getEmail());
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_CHALLENGE, true,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                "SuperAdmin MFA challenge issued", clientIp, userAgent);
        return LoginResponseDTO.mfaRequired(
                superAdmin.getName(),
                superAdmin.getEmail(),
                superAdmin.getPublicId(),
                challenge.id(),
                challenge.expiresInSeconds());
    }

    private boolean isSuperAdminLoginLocked(SuperAdmin superAdmin) {
        LocalDateTime lockedUntil = superAdmin.getLockedUntil();
        if (lockedUntil == null) {
            return false;
        }
        if (LocalDateTime.now().isBefore(lockedUntil)) {
            return true;
        }
        resetSuperAdminPasswordFailures(superAdmin);
        return false;
    }

    private void recordFailedSuperAdminPassword(SuperAdmin superAdmin) {
        int attempts = superAdmin.getFailedLoginAttemptsOrZero() + 1;
        superAdmin.setFailedLoginAttempts(attempts);
        superAdmin.setLastFailedLoginAt(LocalDateTime.now());

        int threshold = Math.max(1, superAdminLockoutMaxAttempts);
        if (attempts >= threshold) {
            superAdmin.setLockedUntil(LocalDateTime.now()
                    .plusSeconds(Math.max(60, superAdminLockoutDurationSeconds)));
            superAdmin.bumpTokenVersion();
            logger.warn("SuperAdmin temporarily locked after {} failed attempts: {}",
                    attempts, superAdmin.getEmail());
        }
        superAdminRepository.save(superAdmin);
    }

    private void resetSuperAdminPasswordFailures(SuperAdmin superAdmin) {
        if (superAdmin.getFailedLoginAttemptsOrZero() == 0
                && superAdmin.getLastFailedLoginAt() == null
                && superAdmin.getLockedUntil() == null) {
            return;
        }
        superAdmin.setFailedLoginAttempts(0);
        superAdmin.setLastFailedLoginAt(null);
        superAdmin.setLockedUntil(null);
        superAdminRepository.save(superAdmin);
    }

    private void auditSuperAdminMfaFailure(
            SuperAdmin superAdmin, String description, String clientIp, String userAgent) {
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_VERIFY_FAILED, false,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                description, clientIp, userAgent);
    }

    @Override
    public LoginResponseDTO userLogin(LoginRequestDTO request, String clientIp) {

        logger.trace("Entered userLogin()");
        logger.debug("Login request for username: {}", request.getLoginIdentifier());

        // Username alone identifies exactly one account platform-wide (uq_users_username_active), so
        // this needs no tenant discriminator — the tenant is a RESULT of the lookup, not an input to
        // it. Email cannot play this role any more: an office may share one address, so a lookup by
        // email would match several rows and throw NonUniqueResultException from an unauthenticated
        // code path, before the password check.
        //
        // Normalized through the same UsernamePolicy every writer uses; the index is case-sensitive
        // and all stored usernames are lowercase, so an un-normalized lookup would just miss.
        // Soft-deleted users are never found — they cannot authenticate.
        String username = UsernamePolicy.normalize(request.getLoginIdentifier());
        User user = (username == null ? Optional.<User>empty()
                                      : userRepository.findByUsernameAndDeletedAtIsNull(username))
                .orElseThrow(() -> {
                    logger.warn("User not found: {}", username);
                    return new BadCredentialsException("Invalid username or password");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            logger.warn("Password mismatch for user: {}", username);
            throw new BadCredentialsException("Invalid username or password");
        }

        // Deactivated accounts cannot log in. Checked only after a correct password so
        // we don't reveal account state to someone who doesn't already hold the credentials.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            logger.warn("Login blocked for inactive user: {}", username);
            throw new BusinessException(
                    "Your account is inactive. Please contact your administrator.",
                    HttpStatus.FORBIDDEN);
        }

        // Tenant lifecycle gate — the teeth behind a SuperAdmin suspend/expire/soft-delete: a
        // non-operational organization blocks ALL of its staff from logging in. Checked after a
        // correct password so tenant state is never revealed to a non-credential-holder.
        Tenant tenant = tenantRepository.findById(user.getTenantId()).orElse(null);
        if (tenant == null || !tenant.isOperational()) {
            logger.warn("Login blocked — organization not operational for user: {}", username);
            throw new BusinessException(
                    "Your organization's account is not active. Please contact support.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user);
        logger.info("User logged in: {}", username);

        // Capture the staff member's IP into the tenant's "home IP" set — best-effort, never blocks login.
        staffIpService.recordStaffIp(user.getTenantId(), clientIp);

        // Audit trail — best-effort; a logging failure must never block a valid login.
        activityLogRecorder.safeRecord(
                ActivityAction.Login,
                "User logged in from IP: " + clientIp,
                user.getId(), user.getName(), user.getUsername(), user.getEmail(),
                ActivityLogRecorder.labelFor(user.getRole()),
                user.getTenantId(), clientIp, null);

        LoginResponseDTO response = new LoginResponseDTO(
                user.getName(),
                "Login successful",
                token,
                "Bearer",
                user.getPublicId(),
                user.getEmail(),
                user.getRole().name()
        );
        // Echo the resolved username so the client can display/cache the identity it must send back
        // on the next login, rather than the (now non-unique) email it may have typed.
        response.setUsername(user.getUsername());
        return response;
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
