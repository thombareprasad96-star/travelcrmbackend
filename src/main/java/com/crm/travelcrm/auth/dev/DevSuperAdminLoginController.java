package com.crm.travelcrm.auth.dev;

import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Local-development sign-in for the platform console: mints a SuperAdmin token with no password
 * and no TOTP step.
 *
 * <p>Exists because the real path is deliberately expensive — password, mandatory authenticator
 * enrolment, then a 6-digit code — and every reset of the local database throws that enrolment
 * away. That cost is correct in production and pure friction on a laptop.
 *
 * <p>Four independent guards keep it off anywhere else, so no single mistake exposes it:
 * <ol>
 *   <li>the bean only exists when {@code app.super-admin.dev-login.enabled=true} — otherwise the
 *       route is not mapped at all and the endpoint 404s;</li>
 *   <li>{@link DevSuperAdminLoginMode} vetoes the flag on the {@code prod} profile;</li>
 *   <li>{@code ProductionConfigValidator} refuses to boot prod with the flag on;</li>
 *   <li>the caller's socket address must be loopback — checked against
 *       {@code getRemoteAddr()}, never {@code X-Forwarded-For}, which any client can send.</li>
 * </ol>
 *
 * <p>Nothing about the account is mutated: no password reset, no MFA secret planted. Flipping the
 * flag back off restores the normal login path exactly as it was. Every use is written to the
 * platform audit trail, marked as a bypass.
 */
@RestController
@RequestMapping("/api/auth/superadmin")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.super-admin.dev-login", name = "enabled", havingValue = "true")
public class DevSuperAdminLoginController {

    private static final Logger log = LogManager.getLogger(DevSuperAdminLoginController.class);

    private final DevSuperAdminLoginMode devLoginMode;
    private final SuperAdminRepository superAdminRepository;
    private final JwtUtil jwtUtil;
    private final PlatformAuditRecorder platformAuditRecorder;

    /** Which account to sign in as when the request names none. Blank ⇒ the oldest enabled one. */
    @Value("${app.super-admin.dev-login.email:}")
    private String defaultEmail;

    public record DevLoginRequest(String email) {}

    @PostMapping("/dev-login")
    public ResponseEntity<LoginResponseDTO> devLogin(
            @RequestBody(required = false) DevLoginRequest request,
            HttpServletRequest httpRequest) {

        // The bean would not exist with the flag off, but it can exist with the prod profile on —
        // the mode bean is where that combination is resolved, so ask it rather than the flag.
        if (!devLoginMode.isEnabled()) {
            throw new BusinessException("Not found.", HttpStatus.NOT_FOUND);
        }
        requireLoopback(httpRequest);

        String requested = request == null || request.email() == null
                ? defaultEmail
                : request.email();
        SuperAdmin superAdmin = resolveAccount(requested);

        if (!superAdmin.isEnabled()) {
            throw new BusinessException(
                    "That platform account is disabled.", HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(superAdmin);
        log.warn("DEV SuperAdmin sign-in (password and MFA bypassed): {}", superAdmin.getEmail());

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.LOGIN, true,
                superAdmin.getId(), superAdmin.getEmail(),
                null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                "DEV sign-in — password and MFA bypassed (app.super-admin.dev-login.enabled)",
                ClientIp.resolve(httpRequest), httpRequest.getHeader("User-Agent"));

        LoginResponseDTO response = new LoginResponseDTO(
                superAdmin.getName(),
                "Signed in (local dev bypass)",
                token,
                "Bearer",
                superAdmin.getPublicId(),
                superAdmin.getEmail(),
                "SUPER_ADMIN");
        // The console routes to /console/setup on either flag. Setup is genuinely not required on
        // this path: SuperAdminSetupCompletionFilter stands down while the bypass is active, and
        // there is no password to change because none was used.
        response.setMustChangePassword(false);
        response.setSetupRequired(false);
        return ResponseEntity.ok(response);
    }

    /**
     * Named account, else the configured default, else the oldest enabled account — which on a
     * freshly seeded local database is the one bootstrap SuperAdmin, so the common case needs no
     * body at all.
     */
    private SuperAdmin resolveAccount(String email) {
        if (email != null && !email.isBlank()) {
            String normalized = email.trim().toLowerCase(Locale.ROOT);
            return superAdminRepository.findByEmailAndDeletedAtIsNull(normalized)
                    .orElseThrow(() -> new BusinessException(
                            "No platform account with email " + normalized + " in this database.",
                            HttpStatus.NOT_FOUND));
        }
        return superAdminRepository.findAllByDeletedAtIsNullOrderByCreatedAtAsc().stream()
                .filter(SuperAdmin::isEnabled)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "This database has no enabled platform account to sign in as.",
                        HttpStatus.NOT_FOUND));
    }

    /**
     * Socket peer only. {@link ClientIp#resolve} prefers {@code X-Forwarded-For}, which is a
     * request header and therefore attacker-controlled — using it here would let any remote caller
     * claim to be localhost. It is still the right source for the audit row below, just not for a
     * decision.
     */
    private void requireLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            log.warn("Rejected dev SuperAdmin sign-in from non-loopback address {}", remote);
            throw new BusinessException(
                    "Local dev sign-in is only available from this machine.", HttpStatus.FORBIDDEN);
        }
    }

    private boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            // A bare IP literal, so this never performs a DNS lookup.
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
