package com.crm.travelcrm.platform.user.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.user.dto.PlatformUserResponse;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlatformUserServiceImpl implements PlatformUserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformUserResponse> listUsers(String search, UUID tenantPublicId, Pageable pageable) {
        // FE knows tenants by publicId; resolve to the internal id (unknown → -1 = no rows).
        Long tenantId = null;
        if (tenantPublicId != null) {
            tenantId = tenantRepository.findByPublicId(tenantPublicId).map(Tenant::getId).orElse(-1L);
        }

        Page<User> page = userRepository.platformSearch(
                search == null ? "" : search.trim(), tenantId, pageable);

        List<Long> tenantIds = page.getContent().stream()
                .map(User::getTenantId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Tenant> tenants = tenantIds.isEmpty()
                ? Map.of()
                : tenantRepository.findAllById(tenantIds).stream()
                        .collect(Collectors.toMap(Tenant::getId, t -> t));

        return page.map(u -> toResponse(u, tenants.get(u.getTenantId())));
    }

    @Override
    @Transactional
    public PlatformUserResponse lock(UUID publicId) {
        User user = requireUser(publicId);
        user.setLocked(true);
        user.setLockedAt(LocalDateTime.now());
        user.setLockedBy(currentActorEmail());
        user.setTokenVersion(user.getTokenVersionOrZero() + 1);   // revoke live sessions
        userRepository.save(user);
        Tenant tenant = tenantOf(user);
        audit(PlatformAuditAction.USER_LOCK, user, tenant,
                "Locked user " + user.getEmail() + " (sessions revoked)");
        return toResponse(user, tenant);
    }

    @Override
    @Transactional
    public PlatformUserResponse unlock(UUID publicId) {
        User user = requireUser(publicId);
        user.setLocked(false);
        user.setLockedAt(null);
        user.setLockedBy(null);
        userRepository.save(user);
        Tenant tenant = tenantOf(user);
        audit(PlatformAuditAction.USER_UNLOCK, user, tenant, "Unlocked user " + user.getEmail());
        return toResponse(user, tenant);
    }

    @Override
    @Transactional
    public PlatformUserResponse resetPassword(UUID publicId, String newPassword) {
        User user = requireUser(publicId);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersionOrZero() + 1);   // "reset + kick": revoke live sessions
        userRepository.save(user);
        Tenant tenant = tenantOf(user);
        audit(PlatformAuditAction.USER_FORCE_RESET, user, tenant,
                "Force-reset password for " + user.getEmail() + " (sessions revoked)");
        return toResponse(user, tenant);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Platform-level cross-tenant lookup by publicId (intentionally not tenant-scoped). */
    private User requireUser(UUID publicId) {
        return userRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + publicId));
    }

    private Tenant tenantOf(User user) {
        return user.getTenantId() == null ? null
                : tenantRepository.findById(user.getTenantId()).orElse(null);
    }

    private void audit(PlatformAuditAction action, User user, Tenant tenant, String description) {
        platformAuditRecorder.safeRecord(action, true,
                user.getTenantId(), tenant != null ? tenant.getOrganizationCode() : null,
                "USER", user.getPublicId(), description);
    }

    private String currentActorEmail() {
        PlatformActor actor = PlatformContext.getActor();
        return actor != null ? actor.email() : "system";
    }

    private PlatformUserResponse toResponse(User u, Tenant t) {
        return PlatformUserResponse.builder()
                .publicId(u.getPublicId())
                .name(u.getName())
                .email(u.getEmail())
                .role(u.getRole())
                .phoneNumber(u.getPhoneNumber())
                .tenantCode(t != null ? t.getOrganizationCode() : null)
                .tenantName(t != null ? t.getOrganizationName() : null)
                .active(Boolean.TRUE.equals(u.getIsActive()))
                .locked(Boolean.TRUE.equals(u.getLocked()))
                .lockedAt(u.getLockedAt())
                .lockedBy(u.getLockedBy())
                .createdAt(u.getCreatedAt())
                .build();
    }
}