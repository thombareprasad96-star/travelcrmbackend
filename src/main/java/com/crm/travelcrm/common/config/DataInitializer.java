package com.crm.travelcrm.common.config;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the platform SuperAdmin on first startup.
 *
 * <p>Runs after Hibernate schema creation (ApplicationRunner executes once the
 * context is fully started). If the super_admins table already has a row, this
 * is a no-op.
 *
 * <p>The password comes from the SUPER_ADMIN_PASSWORD environment variable. Under the
 * {@code prod} profile that variable is MANDATORY and a missing one fails the boot.
 * This class is deliberately ungated by {@code app.seed.enabled} — it is not demo data,
 * it is the only way to obtain a platform login — so the dev fallback below would
 * otherwise run on a production first boot and publish a known password on the public
 * login form. The account is also unrecoverable-by-signup afterwards: AuthServiceImpl
 * refuses to register a second SuperAdmin once this row exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String DEFAULT_EMAIL     = "superadmin@travelcrm.com";
    private static final String SUPER_ADMIN_NAME  = "Platform Super Admin";
    private static final String EMAIL_ENV_VAR     = "SUPER_ADMIN_EMAIL";
    private static final String PASSWORD_ENV_VAR  = "SUPER_ADMIN_PASSWORD";
    private static final String FALLBACK_PASSWORD = "Test@123";

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (superAdminRepository.count() > 0) {
            log.debug("SuperAdmin already exists — skipping bootstrap.");
            return;
        }

        String email = environment.getProperty(EMAIL_ENV_VAR);
        if (email == null || email.isBlank()) {
            email = DEFAULT_EMAIL;
        }
        // trim() only — deliberately NOT toLowerCase(). SuperAdmin login resolves the account by an
        // exact, case-sensitive match, so lowercasing here while the operator types the address as
        // they wrote it in the env file would leave the console unable to authenticate the only
        // platform account, on a first boot, with no signup route left to recover through.
        // Store it exactly as configured and the two sides agree by construction.
        email = email.trim();

        String password = environment.getProperty(PASSWORD_ENV_VAR);
        if (password == null || password.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        PASSWORD_ENV_VAR + " is not set. It is required under the 'prod' profile: this "
                        + "runner creates " + email + " on the first boot against an empty "
                        + "database, and without it the account would be created with a password that is "
                        + "public in this repository. Add " + PASSWORD_ENV_VAR + " to "
                        + "/etc/travelcrm/travelcrm.env (openssl rand -base64 24) and restart.");
            }
            password = FALLBACK_PASSWORD;
            log.warn("Environment variable {} is not set — falling back to the default "
                    + "development password. Set {} before running outside local dev.",
                    PASSWORD_ENV_VAR, PASSWORD_ENV_VAR);
        }

        SuperAdmin superAdmin = SuperAdmin.builder()
                .name(SUPER_ADMIN_NAME)
                .email(email)
                .password(passwordEncoder.encode(password))
                .enabled(true)
                .build();
        superAdminRepository.save(superAdmin);

        // Reached only when the table was empty, i.e. the true first run.
        // The password is deliberately NOT logged: log4j2-prod.xml routes WARN to both
        // travelcrm.log and travelcrm-error.log with 90-day retention, and to the journal —
        // so printing it here would persist the live platform credential in three places
        // that outlive the operator reading it.
        log.warn("""

                ============================================================
                  SUPER ADMIN CREATED  (first run only)
                    Email    : {}
                    Password : the value of {} from the environment
                  Log in and change this password immediately.
                ============================================================
                """, email, PASSWORD_ENV_VAR);
    }
}