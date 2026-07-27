package com.crm.travelcrm.auth.mfa;

import com.crm.travelcrm.auth.dto.SuperAdminMfaSetupResponse;
import com.crm.travelcrm.auth.dto.SuperAdminMfaStatusResponse;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SuperAdminMfaService {

    private final SuperAdminRepository superAdminRepository;
    private final TotpService totpService;
    private final AesSecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Value("${app.mfa.issuer:TravelCRM}")
    private String issuer;

    public String issuer() {
        return issuer;
    }

    public String otpAuthUri(String email, String secret) {
        return totpService.otpAuthUri(issuer, email, secret);
    }

    @Transactional
    public String ensureSetupSecret(Long superAdminId, String clientIp, String userAgent) {
        SuperAdmin admin = superAdminRepository.findByIdAndDeletedAtIsNull(superAdminId)
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));
        if (admin.isMfaEnabled()) {
            throw new BusinessException("MFA is already enabled.", HttpStatus.CONFLICT);
        }
        if (admin.getMfaSecretEnc() != null && !admin.getMfaSecretEnc().isBlank()) {
            return cipher.decrypt(admin.getMfaSecretEnc());
        }

        String secret = totpService.generateSecret();
        admin.setMfaSecretEnc(cipher.encrypt(secret));
        superAdminRepository.save(admin);

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_SETUP, true,
                admin.getId(), admin.getEmail(),
                null, null, "SUPER_ADMIN", admin.getPublicId(),
                "Generated mandatory SuperAdmin MFA setup secret", clientIp, userAgent);

        return secret;
    }

    @Transactional(readOnly = true)
    public SuperAdminMfaStatusResponse status(SuperAdmin principal) {
        SuperAdmin admin = requireManaged(principal);
        return statusResponse(admin);
    }

    @Transactional
    public SuperAdminMfaSetupResponse setup(SuperAdmin principal) {
        SuperAdmin admin = requireManaged(principal);
        if (admin.isMfaEnabled()) {
            throw new BusinessException("MFA is already enabled.", HttpStatus.CONFLICT);
        }

        String secret = totpService.generateSecret();
        admin.setMfaSecretEnc(cipher.encrypt(secret));
        superAdminRepository.save(admin);

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_SETUP, true,
                null, null, "SUPER_ADMIN", admin.getPublicId(),
                "Generated SuperAdmin MFA setup secret");

        return new SuperAdminMfaSetupResponse(
                false,
                issuer,
                secret,
                totpService.otpAuthUri(issuer, admin.getEmail(), secret));
    }

    @Transactional
    public SuperAdminMfaStatusResponse confirm(SuperAdmin principal, String code) {
        SuperAdmin admin = requireManaged(principal);
        if (admin.isMfaEnabled()) {
            throw new BusinessException("MFA is already enabled.", HttpStatus.CONFLICT);
        }
        if (admin.getMfaSecretEnc() == null || admin.getMfaSecretEnc().isBlank()) {
            throw new BusinessException("Start MFA setup before confirming a code.", HttpStatus.BAD_REQUEST);
        }
        if (!verifyCode(admin, code)) {
            auditFailedCode(admin, PlatformAuditAction.MFA_ENABLE_FAILED,
                    "Invalid MFA confirmation code");
            throw new BusinessException("Invalid MFA code.", HttpStatus.UNAUTHORIZED);
        }

        LocalDateTime now = LocalDateTime.now();
        admin.setMfaEnabled(true);
        admin.setMfaEnabledAt(now);
        admin.setMfaLastUsedAt(now);
        admin.bumpTokenVersion();
        superAdminRepository.save(admin);

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_ENABLED, true,
                null, null, "SUPER_ADMIN", admin.getPublicId(),
                "Enabled SuperAdmin MFA; existing sessions revoked");

        return statusResponse(admin);
    }

    @Transactional
    public SuperAdminMfaStatusResponse disable(SuperAdmin principal, String currentPassword, String code) {
        SuperAdmin admin = requireManaged(principal);
        if (!admin.isMfaEnabled()) {
            throw new BusinessException("MFA is not enabled.", HttpStatus.CONFLICT);
        }
        if (!passwordEncoder.matches(currentPassword, admin.getPassword())) {
            throw new BusinessException("Current password is incorrect.", HttpStatus.BAD_REQUEST);
        }
        if (!verifyCode(admin, code)) {
            auditFailedCode(admin, PlatformAuditAction.MFA_DISABLE_FAILED,
                    "Invalid MFA disable code");
            throw new BusinessException("Invalid MFA code.", HttpStatus.UNAUTHORIZED);
        }

        admin.setMfaEnabled(false);
        admin.setMfaSecretEnc(null);
        admin.setMfaEnabledAt(null);
        admin.bumpTokenVersion();
        superAdminRepository.save(admin);

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_DISABLED, true,
                null, null, "SUPER_ADMIN", admin.getPublicId(),
                "Disabled SuperAdmin MFA; existing sessions revoked");

        return statusResponse(admin);
    }

    public boolean verifyCode(SuperAdmin admin, String code) {
        if (admin == null || admin.getMfaSecretEnc() == null || admin.getMfaSecretEnc().isBlank()) {
            return false;
        }
        String secret = cipher.decrypt(admin.getMfaSecretEnc());
        return totpService.verify(secret, code);
    }

    @Transactional
    public void recordSuccessfulUse(Long superAdminId) {
        superAdminRepository.findByIdAndDeletedAtIsNull(superAdminId).ifPresent(admin -> {
            admin.setMfaLastUsedAt(LocalDateTime.now());
            superAdminRepository.save(admin);
        });
    }

    private SuperAdmin requireManaged(SuperAdmin principal) {
        if (principal == null) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }
        return superAdminRepository.findByIdAndDeletedAtIsNull(principal.getId())
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));
    }

    private SuperAdminMfaStatusResponse statusResponse(SuperAdmin admin) {
        return new SuperAdminMfaStatusResponse(
                admin.isMfaEnabled(),
                admin.getMfaEnabledAt(),
                admin.getMfaLastUsedAt());
    }

    private void auditFailedCode(SuperAdmin admin, PlatformAuditAction action, String description) {
        platformAuditRecorder.safeRecord(
                action, false,
                null, null, "SUPER_ADMIN", admin.getPublicId(), description);
    }
}
