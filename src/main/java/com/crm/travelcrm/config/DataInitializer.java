package com.crm.travelcrm.config;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the platform SuperAdmin on first startup.
 *
 * <p>Runs after Hibernate schema creation (ApplicationRunner executes once the
 * context is fully started). If the super_admins table already has a row, this
 * is a no-op — so the plaintext credential banner below can only ever appear on
 * the very first run against an empty database.
 *
 * <p>The password comes from the SUPER_ADMIN_PASSWORD environment variable.
 * The fallback exists for local development only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String SUPER_ADMIN_EMAIL = "superadmin@travelcrm.com";
    private static final String SUPER_ADMIN_NAME  = "Platform Super Admin";
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

        String password = environment.getProperty(PASSWORD_ENV_VAR);
        if (password == null || password.isBlank()) {
            password = FALLBACK_PASSWORD;
            log.warn("Environment variable {} is not set — falling back to the default "
                    + "development password. Set {} before running outside local dev.",
                    PASSWORD_ENV_VAR, PASSWORD_ENV_VAR);
        }

        SuperAdmin superAdmin = SuperAdmin.builder()
                .name(SUPER_ADMIN_NAME)
                .email(SUPER_ADMIN_EMAIL)
                .password(passwordEncoder.encode(password))
                .enabled(true)
                .build();
        superAdminRepository.save(superAdmin);

        // Reached only when the table was empty, i.e. the true first run.
        log.warn("""

                ============================================================
                  SUPER ADMIN CREATED  (first run only)
                    Email    : {}
                    Password : {}
                  Log in and change this password immediately.
                ============================================================
                """, SUPER_ADMIN_EMAIL, password);
    }
}