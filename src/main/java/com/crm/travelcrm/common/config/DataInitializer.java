package com.crm.travelcrm.common.config;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.security.SuperAdminPasswordPolicy;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bootstraps the fixed platform SuperAdmin allowlist.
 *
 * <p>This runner is deliberately idempotent: it creates a required account only when no row with
 * that email exists, and it never overwrites an existing password or setup state. Legacy active rows
 * outside the allowlist are soft-deleted unless they were created through the approved invite flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final List<BootstrapAccount> REQUIRED_ACCOUNTS = List.of(
            new BootstrapAccount(
                    "Platform Super Admin 1",
                    "rajpoottours2789@gmail.com",
                    "SUPERADMIN_1_EMAIL",
                    "SUPERADMIN_1_PASSWORD"),
            new BootstrapAccount(
                    "Platform Super Admin 2",
                    "thombareprasad96@gmail.com",
                    "SUPERADMIN_2_EMAIL",
                    "SUPERADMIN_2_PASSWORD")
    );

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final SuperAdminPasswordPolicy superAdminPasswordPolicy;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Set<String> allowedEmails = REQUIRED_ACCOUNTS.stream()
                .map(BootstrapAccount::email)
                .collect(Collectors.toUnmodifiableSet());

        softDeleteUnexpectedActiveAccounts(allowedEmails);
        REQUIRED_ACCOUNTS.forEach(this::ensureAccountExists);
    }

    private void softDeleteUnexpectedActiveAccounts(Set<String> allowedEmails) {
        for (SuperAdmin admin : superAdminRepository.findAllByDeletedAtIsNull()) {
            String email = normalize(admin.getEmail());
            if (allowedEmails.contains(email) || admin.isCreatedViaInvite()) {
                continue;
            }
            admin.setEnabled(false);
            admin.softDelete("system-bootstrap");
            admin.bumpTokenVersion();
            superAdminRepository.save(admin);
            log.warn("Soft-deleted non-allowlisted SuperAdmin during bootstrap: {}", admin.getEmail());
        }
    }

    private void ensureAccountExists(BootstrapAccount account) {
        String email = configuredEmail(account);
        superAdminRepository.findByEmail(email).ifPresentOrElse(existing ->
                log.debug("Required SuperAdmin {} already exists; bootstrap will not overwrite it.", email),
                () -> createRequiredAccount(account, email));
    }

    private void createRequiredAccount(BootstrapAccount account, String email) {
        String password = requiredPassword(account.passwordEnv(), email);
        superAdminPasswordPolicy.validate(password);

        SuperAdmin superAdmin = SuperAdmin.builder()
                .name(account.name())
                .email(email)
                .password(passwordEncoder.encode(password))
                .enabled(true)
                .mfaEnabled(false)
                .mustChangePassword(true)
                .tokenVersion(1)
                .failedLoginAttempts(0)
                .build();
        superAdminRepository.save(superAdmin);

        log.warn("""

                ============================================================
                  REQUIRED SUPER ADMIN CREATED
                    Email    : {}
                    Password : value of {}
                  Login requires TOTP enrollment, then password change.
                ============================================================
                """, email, account.passwordEnv());
    }

    private String configuredEmail(BootstrapAccount account) {
        String configured = environment.getProperty(account.emailEnv());
        if (configured == null || configured.isBlank()) {
            configured = account.email();
        }
        String normalized = normalize(configured);
        if (!normalized.equals(account.email())) {
            throw new IllegalStateException(account.emailEnv() + " must be " + account.email()
                    + ". SuperAdmin bootstrap is restricted to the fixed allowlist.");
        }
        return normalized;
    }

    private String requiredPassword(String envVar, String email) {
        String password = environment.getProperty(envVar);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(envVar + " is required to create SuperAdmin " + email
                    + ". Existing rows are never overwritten, so set this only for first bootstrap.");
        }
        return password;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private record BootstrapAccount(String name, String email, String emailEnv, String passwordEnv) {
    }
}
