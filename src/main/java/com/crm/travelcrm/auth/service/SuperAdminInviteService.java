package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.SuperAdminInviteAcceptRequest;
import com.crm.travelcrm.auth.dto.SuperAdminInviteCreateRequest;
import com.crm.travelcrm.auth.dto.SuperAdminInviteResponse;
import com.crm.travelcrm.auth.dto.SuperAdminInviteSetupResponse;
import com.crm.travelcrm.auth.mfa.TotpService;
import com.crm.travelcrm.auth.repository.SuperAdminInviteRepository;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.auth.security.SuperAdminPasswordPolicy;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.entity.SuperAdminInvite;
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
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SuperAdminInviteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SuperAdminInviteRepository inviteRepository;
    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminPasswordPolicy passwordPolicy;
    private final TotpService totpService;
    private final AesSecretCipher cipher;
    private final JwtUtil jwtUtil;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final SuperAdminLoginNotificationService loginNotificationService;

    @Value("${app.super-admin.invite-ttl-hours:24}")
    private long inviteTtlHours;

    @Value("${app.mfa.issuer:TravelCRM}")
    private String issuer;

    @Value("${app.console-base-url:}")
    private String consoleBaseUrl;

    @Transactional
    public SuperAdminInviteResponse create(SuperAdminInviteCreateRequest request) {
        PlatformActor actor = PlatformContext.getActor();
        if (actor == null) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        String email = normalize(request.getEmail());
        if (superAdminRepository.existsByEmail(email)) {
            throw new BusinessException("A SuperAdmin row already exists for " + email + ".", HttpStatus.CONFLICT);
        }

        for (SuperAdminInvite old : inviteRepository
                .findAllByInvitedEmailAndConsumedAtIsNullAndDeletedAtIsNull(email)) {
            old.softDelete(actor.email());
            inviteRepository.save(old);
        }

        String token = randomToken();
        SuperAdminInvite invite = SuperAdminInvite.builder()
                .invitedEmail(email)
                .invitedName(request.getName().trim())
                .tokenHash(hash(token))
                .expiresAt(LocalDateTime.now().plusHours(Math.max(1, inviteTtlHours)))
                .createdBySuperAdminId(actor.superAdminId())
                .createdByEmail(actor.email())
                .build();

        SuperAdminInvite saved = inviteRepository.save(invite);
        platformAuditRecorder.safeRecord(
                PlatformAuditAction.SUPER_ADMIN_INVITE_CREATE, true,
                null, null, "SUPER_ADMIN_INVITE", saved.getPublicId(),
                "Created SuperAdmin invite for " + email);

        return toResponse(saved, token);
    }

    @Transactional(readOnly = true)
    public List<SuperAdminInviteResponse> list() {
        return inviteRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream()
                .map(invite -> toResponse(invite, null))
                .toList();
    }

    @Transactional
    public SuperAdminInviteSetupResponse setup(String token) {
        SuperAdminInvite invite = requireUsableInvite(token);
        String secret;
        if (StringUtils.hasText(invite.getMfaSecretEnc())) {
            secret = cipher.decrypt(invite.getMfaSecretEnc());
        } else {
            secret = totpService.generateSecret();
            invite.setMfaSecretEnc(cipher.encrypt(secret));
            inviteRepository.save(invite);
        }

        return new SuperAdminInviteSetupResponse(
                invite.getInvitedName(),
                invite.getInvitedEmail(),
                invite.getExpiresAt(),
                issuer,
                secret,
                totpService.otpAuthUri(issuer, invite.getInvitedEmail(), secret));
    }

    @Transactional
    public LoginResponseDTO accept(SuperAdminInviteAcceptRequest request, String token,
                                   String clientIp, String userAgent) {
        SuperAdminInvite invite = requireUsableInvite(token);
        passwordPolicy.validate(request.getPassword());

        if (superAdminRepository.existsByEmail(invite.getInvitedEmail())) {
            throw new BusinessException("A SuperAdmin row already exists for this email.", HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(invite.getMfaSecretEnc())) {
            throw new BusinessException("Start invite MFA setup before accepting the invite.",
                    HttpStatus.BAD_REQUEST);
        }

        String secret = cipher.decrypt(invite.getMfaSecretEnc());
        if (!totpService.verify(secret, request.getCode())) {
            throw new BusinessException("Invalid MFA code.", HttpStatus.UNAUTHORIZED);
        }

        LocalDateTime now = LocalDateTime.now();
        SuperAdmin admin = SuperAdmin.builder()
                .name(invite.getInvitedName())
                .email(invite.getInvitedEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .mfaEnabled(true)
                .mfaSecretEnc(invite.getMfaSecretEnc())
                .mfaEnabledAt(now)
                .mfaLastUsedAt(now)
                .createdViaInvite(true)
                .mustChangePassword(false)
                .tokenVersion(1)
                .failedLoginAttempts(0)
                .lastLoginAt(now)
                .lastLoginIp(clientIp)
                .lastLoginUserAgentHash(loginNotificationService.userAgentHash(userAgent))
                .build();

        SuperAdmin saved = superAdminRepository.save(admin);
        invite.setConsumedAt(now);
        inviteRepository.save(invite);

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.SUPER_ADMIN_INVITE_ACCEPT, true,
                saved.getId(), saved.getEmail(),
                null, null, "SUPER_ADMIN", saved.getPublicId(),
                "Accepted SuperAdmin invite created by " + invite.getCreatedByEmail(),
                clientIp, userAgent);
        loginNotificationService.notifyLogin(saved, clientIp, userAgent, true, now);

        String jwt = jwtUtil.generateToken(saved);
        return new LoginResponseDTO(
                saved.getName(),
                "Invite accepted",
                jwt,
                "Bearer",
                saved.getPublicId(),
                saved.getEmail(),
                "SUPER_ADMIN");
    }

    private SuperAdminInvite requireUsableInvite(String token) {
        SuperAdminInvite invite = inviteRepository.findByTokenHashAndDeletedAtIsNull(hash(token))
                .orElseThrow(() -> new BusinessException("Invite token is invalid.", HttpStatus.NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        if (invite.isConsumed()) {
            throw new BusinessException("Invite token has already been used.", HttpStatus.CONFLICT);
        }
        if (invite.isExpired(now)) {
            throw new BusinessException("Invite token has expired.", HttpStatus.GONE);
        }
        return invite;
    }

    private SuperAdminInviteResponse toResponse(SuperAdminInvite invite, String rawToken) {
        return new SuperAdminInviteResponse(
                invite.getPublicId(),
                invite.getInvitedName(),
                invite.getInvitedEmail(),
                invite.getExpiresAt(),
                invite.getConsumedAt(),
                rawToken,
                rawToken == null ? null : acceptUrl(rawToken));
    }

    private String acceptUrl(String token) {
        String path = "/superadmin/invite?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(consoleBaseUrl)) {
            return path;
        }
        return consoleBaseUrl.replaceAll("/+$", "") + path;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException("Invite token is required.", HttpStatus.BAD_REQUEST);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash invite token", ex);
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
