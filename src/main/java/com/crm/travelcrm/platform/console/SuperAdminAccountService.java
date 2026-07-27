package com.crm.travelcrm.platform.console;

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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SuperAdminAccountService {

    private final SuperAdminRepository superAdminRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Transactional(readOnly = true)
    public List<SuperAdminAccountResponse> list() {
        return superAdminRepository.findAllByDeletedAtIsNullOrderByCreatedAtAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SuperAdminAccountResponse resetMfa(UUID targetPublicId, SuperAdmin principal,
                                              String clientIp, String userAgent) {
        SuperAdmin actor = requireManaged(principal);
        SuperAdmin target = superAdminRepository.findByPublicIdAndDeletedAtIsNull(targetPublicId)
                .orElseThrow(() -> new BusinessException("SuperAdmin account not found.", HttpStatus.NOT_FOUND));

        if (target.getId() == actor.getId()) {
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.SUPER_ADMIN_MFA_RESET, false,
                    actor.getId(), actor.getEmail(),
                    null, null, "SUPER_ADMIN", target.getPublicId(),
                    "Blocked self-service SuperAdmin MFA reset for " + target.getEmail(),
                    clientIp, userAgent);
            throw new BusinessException("Another SuperAdmin must reset your MFA.", HttpStatus.FORBIDDEN);
        }

        boolean changed = target.isMfaEnabled()
                || target.getMfaSecretEnc() != null
                || target.getMfaEnabledAt() != null
                || target.getMfaLastUsedAt() != null;

        target.setMfaEnabled(false);
        target.setMfaSecretEnc(null);
        target.setMfaEnabledAt(null);
        target.setMfaLastUsedAt(null);
        if (changed) {
            target.bumpTokenVersion();
        }

        SuperAdmin saved = superAdminRepository.save(target);
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.SUPER_ADMIN_MFA_RESET, true,
                actor.getId(), actor.getEmail(),
                null, null, "SUPER_ADMIN", saved.getPublicId(),
                "Reset MFA for SuperAdmin " + saved.getEmail() + "; re-enrollment required on next login",
                clientIp, userAgent);

        return toResponse(saved);
    }

    private SuperAdmin requireManaged(SuperAdmin principal) {
        if (principal == null) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }
        return superAdminRepository.findByIdAndDeletedAtIsNull(principal.getId())
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));
    }

    private SuperAdminAccountResponse toResponse(SuperAdmin admin) {
        LocalDateTime lockedUntil = admin.getLockedUntil();
        boolean locked = lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
        return new SuperAdminAccountResponse(
                admin.getPublicId(),
                admin.getName(),
                admin.getEmail(),
                admin.isEnabled(),
                admin.isMfaEnabled(),
                admin.isMustChangePassword(),
                admin.isSetupComplete(),
                admin.isCreatedViaInvite(),
                locked,
                admin.getFailedLoginAttemptsOrZero(),
                lockedUntil,
                admin.getLastLoginAt(),
                admin.getLastLoginIp(),
                admin.getCreatedAt());
    }
}
