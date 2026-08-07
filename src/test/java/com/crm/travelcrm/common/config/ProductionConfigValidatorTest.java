package com.crm.travelcrm.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigValidatorTest {

    private static final String GOOD_JWT =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String GOOD_PORTAL_JWT =
            Base64.getEncoder().encodeToString("ZYXWVUTSRQPONMLKJIHGFEDCBA987654".getBytes());
    private static final String GOOD_AES_256 =
            Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());

    private final ProductionConfigValidator validator = new ProductionConfigValidator();

    private MockEnvironment validEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("jwt.secret", GOOD_JWT);
        env.setProperty("portal.jwt.secret", GOOD_PORTAL_JWT);
        env.setProperty("app.encryption.key", GOOD_AES_256);
        env.setProperty("spring.datasource.password", "a-real-db-password");
        env.setProperty("app.public-base-url", "https://api.mytripsafar.com");
        env.setProperty("app.cors.allowed-origins", "https://mytripsafar.com,https://www.mytripsafar.com");
        env.setProperty("SUPERADMIN_1_EMAIL", "rajpoottours2789@gmail.com");
        env.setProperty("SUPERADMIN_1_PASSWORD", "a-real-super-admin-password-1!");
        env.setProperty("SUPERADMIN_2_EMAIL", "thombareprasad96@gmail.com");
        env.setProperty("SUPERADMIN_2_PASSWORD", "a-real-super-admin-password-2!");
        env.setProperty("spring.mail.username", "vetotechit@gmail.com");
        env.setProperty("app.super-admin.login-alerts.from-email", "vetotechit@gmail.com");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        env.setProperty("app.seed.enabled", "false");
        return env;
    }

    @Test
    @DisplayName("a correctly filled production environment boots")
    void validConfigPasses() {
        assertThatCode(() -> validator.validate(validEnv())).doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("fixed SuperAdmin bootstrap accounts")
    class SuperAdminBootstrap {

        @Test
        @DisplayName("wrong allowlist email is rejected")
        void wrongSuperAdminEmail() {
            MockEnvironment env = validEnv();
            env.setProperty("SUPERADMIN_1_EMAIL", "somebody@example.com");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPERADMIN_1_EMAIL")
                    .hasMessageContaining("rajpoottours2789@gmail.com");
        }

        @Test
        @DisplayName("missing bootstrap password is rejected")
        void missingBootstrapPassword() {
            MockEnvironment env = validEnv();
            env.setProperty("SUPERADMIN_2_PASSWORD", "");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPERADMIN_2_PASSWORD");
        }

        @Test
        @DisplayName("placeholder bootstrap password is rejected")
        void placeholderBootstrapPassword() {
            MockEnvironment env = validEnv();
            env.setProperty("SUPERADMIN_1_PASSWORD", "CHANGE_ME");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPERADMIN_1_PASSWORD");
        }
    }

    @Nested
    @DisplayName("platform CRM mailbox")
    class PlatformMailbox {

        @Test
        @DisplayName("personal Gmail SMTP account is rejected for SuperAdmin mail")
        void personalGmailRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.mail.username", "thombarep96@gmail.com");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.mail.username")
                    .hasMessageContaining("vetotechit@gmail.com");
        }

        @Test
        @DisplayName("wrong SuperAdmin alert From address is rejected")
        void wrongAlertFromRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("app.super-admin.login-alerts.from-email", "thombarep96@gmail.com");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.super-admin.login-alerts.from-email")
                    .hasMessageContaining("vetotechit@gmail.com");
        }
    }

    @Nested
    @DisplayName("local dev SuperAdmin sign-in")
    class DevSuperAdminLogin {

        @Test
        @DisplayName("the no-password / no-MFA bypass is rejected in prod")
        void devLoginRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("app.super-admin.dev-login.enabled", "true");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.super-admin.dev-login.enabled")
                    .hasMessageContaining("no password and no MFA");
        }

        @Test
        @DisplayName("explicitly false is fine")
        void devLoginOffAccepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.super-admin.dev-login.enabled", "false");

            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("app.encryption.key must be a usable AES key")
    class EncryptionKey {

        @Test
        @DisplayName("non-Base64 is rejected here, not at first use")
        void notBase64() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", "not_base64_!!!_at_all");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.encryption.key");
        }

        @Test
        @DisplayName("valid Base64 of a wrong length is rejected")
        void wrongLength() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", Base64.getEncoder().encodeToString("tooshort".getBytes()));
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AES needs 16, 24 or 32");
        }

        @Test
        @DisplayName("AES-128 is accepted")
        void aes128Accepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", Base64.getEncoder().encodeToString("0123456789abcdef".getBytes()));
            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ddl-auto")
    class DdlAuto {

        @Test
        @DisplayName("create is rejected")
        void createRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.jpa.hibernate.ddl-auto", "create");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DESTROYS");
        }

        @Test
        @DisplayName("update, validate, and none are accepted")
        void safeValuesAccepted() {
            for (String safe : new String[]{"update", "validate", "none", "UPDATE", " validate "}) {
                MockEnvironment env = validEnv();
                env.setProperty("spring.jpa.hibernate.ddl-auto", safe);
                assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("Flyway production cutover")
    class FlywayCutover {

        @Test
        @DisplayName("Flyway enabled with Hibernate update is rejected")
        void flywayRequiresHibernateValidate() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.flyway.enabled", "true");
            env.setProperty("spring.jpa.hibernate.ddl-auto", "update");
            env.setProperty("spring.sql.init.mode", "never");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Flyway owns schema changes")
                    .hasMessageContaining("JPA_DDL_AUTO=validate");
        }

        @Test
        @DisplayName("Flyway enabled with Hibernate validate is accepted")
        void flywayWithHibernateValidateAccepted() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.flyway.enabled", "true");
            env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
            env.setProperty("spring.sql.init.mode", "never");

            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Flyway enabled with Spring SQL init is rejected")
        void flywayRejectsSqlInitReplay() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.flyway.enabled", "true");
            env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
            env.setProperty("spring.sql.init.mode", "always");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.sql.init.mode")
                    .hasMessageContaining("SQL_INIT_MODE=never");
        }

        @Test
        @DisplayName("baseline-on-migrate requires a one-time explicit allowance")
        void baselineOnMigrateRequiresAllowance() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.flyway.baseline-on-migrate", "true");

            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("baseline-on-migrate")
                    .hasMessageContaining("app.flyway.allow-baseline-on-migrate=true");
        }

        @Test
        @DisplayName("baseline-on-migrate is accepted only with explicit allowance")
        void baselineOnMigrateWithAllowanceAccepted() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.flyway.baseline-on-migrate", "true");
            env.setProperty("app.flyway.allow-baseline-on-migrate", "true");

            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("CORS origins")
    class Cors {

        @Test
        @DisplayName("a plain http:// origin is rejected")
        void httpOriginRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("app.cors.allowed-origins", "http://mytripsafar.com");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("http://");
        }

        @Test
        @DisplayName("apex plus www over https is accepted")
        void httpsOriginsAccepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.cors.allowed-origins", "https://mytripsafar.com,https://www.mytripsafar.com");
            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("secrets that are public in git history")
    class DevValues {

        @Test
        @DisplayName("the dev jwt.secret is rejected")
        void devJwtSecret() {
            MockEnvironment env = validEnv();
            env.setProperty("jwt.secret", "4Y7bN9vR+KzX2pQW5mE9JtGvC3sX8zK1rN6xP4vM1bA=");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("git history");
        }

        @Test
        @DisplayName("identical staff and portal keys are rejected")
        void identicalRealmKeys() {
            MockEnvironment env = validEnv();
            env.setProperty("portal.jwt.secret", GOOD_JWT);
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IDENTICAL");
        }
    }

    @Test
    @DisplayName("every problem is collected into one message")
    void collectsAllProblems() {
        MockEnvironment env = validEnv();
        env.setProperty("SUPERADMIN_1_EMAIL", "wrong@example.com");
        env.setProperty("SUPERADMIN_2_PASSWORD", "CHANGE_ME");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "create");
        env.setProperty("app.cors.allowed-origins", "http://mytripsafar.com");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("SUPERADMIN_1_EMAIL")
                        .contains("SUPERADMIN_2_PASSWORD")
                        .contains("DESTROYS")
                        .contains("http://"));
    }
}
