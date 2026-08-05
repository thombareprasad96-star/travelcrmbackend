package com.crm.travelcrm.auth.dev;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Single authority for "is the local no-password SuperAdmin sign-in active?".
 *
 * <p>Two independent conditions must hold: the {@code app.super-admin.dev-login.enabled} flag AND
 * the absence of the {@code prod} profile. The profile veto lives here rather than only on the
 * controller so that every consumer (the endpoint, {@code SuperAdminSetupCompletionFilter}) reads
 * the same answer — a flag that is true on a prod boot must not half-enable the bypass.
 *
 * <p>{@code ProductionConfigValidator} refuses to start the prod profile with the flag on, so in
 * practice that combination never reaches this bean; the veto is the belt to that braces. The
 * startup banner exists because a bypass nobody notices is the dangerous kind.
 */
@Component
public class DevSuperAdminLoginMode {

    private static final Logger log = LogManager.getLogger(DevSuperAdminLoginMode.class);

    private final boolean enabled;

    public DevSuperAdminLoginMode(
            Environment environment,
            @Value("${app.super-admin.dev-login.enabled:false}") boolean flagged) {

        boolean prodProfile = environment.acceptsProfiles(Profiles.of("prod"));
        this.enabled = flagged && !prodProfile;

        if (flagged && prodProfile) {
            log.error("app.super-admin.dev-login.enabled is TRUE on the prod profile — IGNORED. "
                    + "Set APP_SUPER_ADMIN_DEV_LOGIN_ENABLED=false.");
        } else if (this.enabled) {
            log.warn("");
            log.warn("═══════════════════════════════════════════════════════════════════");
            log.warn(" DEV SUPERADMIN SIGN-IN IS ENABLED");
            log.warn(" POST /api/auth/superadmin/dev-login mints a platform token with NO");
            log.warn(" password and NO MFA. Loopback callers only. Local development only.");
            log.warn(" Turn it off with app.super-admin.dev-login.enabled=false");
            log.warn("═══════════════════════════════════════════════════════════════════");
            log.warn("");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
