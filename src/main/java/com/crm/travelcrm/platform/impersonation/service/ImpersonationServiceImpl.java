package com.crm.travelcrm.platform.impersonation.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.security.JwtClaims;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.impersonation.dto.ImpersonationResponse;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImpersonationServiceImpl implements ImpersonationService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final JwtUtil jwtUtil;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Override
    @Transactional  // read-write: records a PlatformAuditLog (IMPERSONATION_START) — never read-only
    public ImpersonationResponse start(UUID userPublicId) {
        // H1: a soft-deleted user is never resolvable → can never be impersonated.
        User target = userRepository.findByPublicIdAndDeletedAtIsNull(userPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userPublicId));

        if (target.getTenantId() == null) {
            throw new BusinessException("Cannot impersonate a platform user.", HttpStatus.BAD_REQUEST);
        }
        // Respect user-level control: an inactive/locked user cannot be impersonated (and the
        // JwtAuthFilter would reject such a token on the first request anyway).
        if (!target.isEnabled()) {
            throw new BusinessException("Cannot impersonate a deactivated user.", HttpStatus.CONFLICT);
        }
        if (!target.isAccountNonLocked()) {
            throw new BusinessException("Cannot impersonate a locked user.", HttpStatus.CONFLICT);
        }

        PlatformActor actor = PlatformContext.getActor();
        if (actor == null) {
            throw new IllegalStateException("Impersonation requires an authenticated SuperAdmin.");
        }

        String token = jwtUtil.generateImpersonationToken(target, actor.superAdminId(), actor.email());
        Tenant tenant = tenantRepository.findById(target.getTenantId()).orElse(null);

        platformAuditRecorder.safeRecord(PlatformAuditAction.IMPERSONATION_START, true,
                target.getTenantId(), tenant != null ? tenant.getOrganizationCode() : null,
                "USER", target.getPublicId(),
                "Started impersonating " + target.getEmail()
                        + (tenant != null ? " @ " + tenant.getOrganizationCode() : ""));

        return ImpersonationResponse.builder()
                .token(token)
                .expiresInMs(jwtUtil.getImpersonationExpiryMs())
                .name(target.getName())
                .email(target.getEmail())
                .role(target.getRole().name())
                .tenantCode(tenant != null ? tenant.getOrganizationCode() : null)
                .tenantName(tenant != null ? tenant.getOrganizationName() : null)
                .build();
    }

    @Override
    @Transactional  // read-write: records a PlatformAuditLog (IMPERSONATION_END) — never read-only
    public void end(String impersonationToken) {
        // Only a valid impersonation token produces an END record; anything else is a no-op.
        if (impersonationToken == null
                || !jwtUtil.isTokenValid(impersonationToken)
                || !JwtClaims.TYPE_IMPERSONATION.equals(jwtUtil.extractTokenType(impersonationToken))) {
            return;
        }

        String targetEmail = jwtUtil.extractEmail(impersonationToken);
        Long tenantId = jwtUtil.extractTenantId(impersonationToken);
        Long impersonatorId = jwtUtil.extractImpersonatorId(impersonationToken);
        String impersonatorEmail = jwtUtil.extractImpersonatorEmail(impersonationToken);

        UUID targetPublicId = (tenantId != null)
                ? userRepository.findByEmailAndTenantIdAndDeletedAtIsNull(targetEmail, tenantId)
                        .map(User::getPublicId).orElse(null)
                : null;
        String tenantCode = (tenantId != null)
                ? tenantRepository.findById(tenantId).map(Tenant::getOrganizationCode).orElse(null)
                : null;

        // The request carries the impersonation token (a tenant realm principal), so PlatformContext
        // is NOT set — attribute the END to the impersonator taken from the token's claims.
        platformAuditRecorder.safeRecord(PlatformAuditAction.IMPERSONATION_END, true,
                impersonatorId, impersonatorEmail,
                tenantId, tenantCode, "USER", targetPublicId,
                "Ended impersonating " + targetEmail, null, null);
    }
}