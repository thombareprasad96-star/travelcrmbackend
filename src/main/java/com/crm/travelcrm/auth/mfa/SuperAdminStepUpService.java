package com.crm.travelcrm.auth.mfa;

import com.crm.travelcrm.auth.dev.DevSuperAdminLoginMode;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SuperAdminStepUpService {

    public static final String MFA_CODE_HEADER = "X-SuperAdmin-Mfa-Code";

    private final SuperAdminRepository superAdminRepository;
    private final SuperAdminMfaService mfaService;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final DevSuperAdminLoginMode devLoginMode;

    @Transactional
    public void requireCode(SuperAdmin principal, String code, String action,
                            String clientIp, String userAgent) {
        if (principal == null) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        // Local development only. This is the single gate every high-consequence console action
        // funnels through (tenant delete, plan change, marketplace approve/cancel, hotel publish,
        // sub-agent licensing). With MFA never enrolled on a local database it rejects all of them
        // outright — "MFA is required for this action" — which makes the console untestable.
        // Non-prod only, and the audit row below still records the action; see DevSuperAdminLoginMode.
        if (devLoginMode.isEnabled()) {
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.MFA_VERIFY, true,
                    principal.getId(), principal.getEmail(),
                    null, null, "SUPER_ADMIN", principal.getPublicId(),
                    "DEV — step-up MFA bypassed for " + action, clientIp, userAgent);
            return;
        }

        SuperAdmin admin = superAdminRepository.findByIdAndDeletedAtIsNull(principal.getId())
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));

        if (!admin.isMfaEnabled()) {
            throw new BusinessException("MFA is required for this action.", HttpStatus.FORBIDDEN);
        }
        if (code == null || !code.matches("^\\d{6}$")) {
            throw new BusinessException("MFA code must be 6 digits.", HttpStatus.BAD_REQUEST);
        }
        if (!mfaService.verifyCode(admin, code)) {
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.MFA_VERIFY_FAILED, false,
                    admin.getId(), admin.getEmail(),
                    null, null, "SUPER_ADMIN", admin.getPublicId(),
                    "Invalid step-up MFA code for " + action, clientIp, userAgent);
            throw new BusinessException("Invalid MFA code.", HttpStatus.UNAUTHORIZED);
        }

        admin.setMfaLastUsedAt(LocalDateTime.now());
        superAdminRepository.save(admin);
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.MFA_VERIFY, true,
                admin.getId(), admin.getEmail(),
                null, null, "SUPER_ADMIN", admin.getPublicId(),
                "Step-up MFA verified for " + action, clientIp, userAgent);
    }
}
