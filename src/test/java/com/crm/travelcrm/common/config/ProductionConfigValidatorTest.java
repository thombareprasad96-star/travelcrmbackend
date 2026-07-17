package com.crm.travelcrm.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the boot gate that stands between a mistyped env file and a live deployment.
 *
 * <p>Both directions are load-bearing here, and the FALSE-POSITIVE direction is the one with no
 * safety net: this validator runs as a BeanFactoryPostProcessor on a remote VPS, so a check that
 * wrongly rejects a valid configuration fails the boot with a message the operator has to decode
 * over SSH. Every "rejects" test below therefore has a "accepts the valid form" counterpart.
 */
class ProductionConfigValidatorTest {

    /** 32 random-ish bytes, Base64 — the shape `openssl rand -base64 32` produces. */
    private static final String GOOD_JWT =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdefX".substring(0, 32).getBytes());
    private static final String GOOD_PORTAL_JWT =
            Base64.getEncoder().encodeToString("ZYXWVUTSRQPONMLKJIHGFEDCBA987654".getBytes());
    private static final String GOOD_AES_256 =
            Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());   // 32 bytes

    private final ProductionConfigValidator validator = new ProductionConfigValidator();

    /** A fully valid production environment. Each test mutates exactly one property off this. */
    private MockEnvironment validEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("jwt.secret", GOOD_JWT);
        env.setProperty("portal.jwt.secret", GOOD_PORTAL_JWT);
        env.setProperty("superadmin.signup-secret", "a-long-random-signup-secret-value");
        env.setProperty("app.encryption.key", GOOD_AES_256);
        env.setProperty("spring.datasource.password", "a-real-db-password");
        env.setProperty("app.public-base-url", "https://api.mytripsafar.com");
        env.setProperty("app.cors.allowed-origins", "https://mytripsafar.com,https://www.mytripsafar.com");
        env.setProperty("SUPER_ADMIN_PASSWORD", "a-real-super-admin-password");
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
    @DisplayName("placeholders left in the env file")
    class Placeholders {

        @Test
        @DisplayName("CHANGE_ME signup secret is rejected — /api/auth/** is permitAll")
        void changeMeSignupSecret() {
            MockEnvironment env = validEnv();
            env.setProperty("superadmin.signup-secret", "CHANGE_ME");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("superadmin.signup-secret");
        }

        @Test
        @DisplayName("blank signup secret is rejected — constantTimeEquals(\"\",\"\") returns true")
        void blankSignupSecret() {
            MockEnvironment env = validEnv();
            env.setProperty("superadmin.signup-secret", "   ");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("superadmin.signup-secret");
        }

        @Test
        @DisplayName("CHANGE_ME encryption key is rejected before AesSecretCipher can die on it")
        void changeMeEncryptionKey() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", "CHANGE_ME");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.encryption.key");
        }

        @Test
        @DisplayName("a placeholder is reported once, not twice")
        void placeholderReportedOnce() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", "CHANGE_ME");
            assertThatThrownBy(() -> validator.validate(env))
                    .hasMessageNotContaining("not valid Base64");   // requireAesKey must stay quiet
        }
    }

    @Nested
    @DisplayName("SUPER_ADMIN_PASSWORD — DataInitializer creates the platform account from it")
    class SuperAdminPassword {

        @Test
        @DisplayName("missing is rejected, or the account gets this repo's public fallback password")
        void missing() {
            MockEnvironment env = validEnv();
            env.setProperty("SUPER_ADMIN_PASSWORD", "");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPER_ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("CHANGE_ME is rejected")
        void placeholder() {
            MockEnvironment env = validEnv();
            env.setProperty("SUPER_ADMIN_PASSWORD", "CHANGE_ME");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPER_ADMIN_PASSWORD");
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
        @DisplayName("AES-128 (16 bytes) is ACCEPTED — the >=32 HMAC rule must not leak onto this key")
        void aes128Accepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key", Base64.getEncoder().encodeToString("0123456789abcdef".getBytes()));
            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AES-192 (24 bytes) is ACCEPTED")
        void aes192Accepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.encryption.key",
                    Base64.getEncoder().encodeToString("0123456789abcdef01234567".getBytes()));
            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ddl-auto — the one destructive setting reachable from the env file")
    class DdlAuto {

        @Test
        @DisplayName("create is rejected — it drops every table on each restart")
        void createRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.jpa.hibernate.ddl-auto", "create");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DESTROYS");
        }

        @Test
        @DisplayName("create-drop is rejected")
        void createDropRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("spring.jpa.hibernate.ddl-auto", "create-drop");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DESTROYS");
        }

        @Test
        @DisplayName("validate and none are accepted — the documented migration path must not be blocked")
        void safeValuesAccepted() {
            for (String safe : new String[]{"update", "validate", "none", "UPDATE", " validate "}) {
                MockEnvironment env = validEnv();
                env.setProperty("spring.jpa.hibernate.ddl-auto", safe);
                assertThatCode(() -> validator.validate(env))
                        .as("ddl-auto=%s must be accepted", safe)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("CORS origins")
    class Cors {

        @Test
        @DisplayName("a plain http:// origin is rejected — the SPA always sends Origin: https://")
        void httpOriginRejected() {
            MockEnvironment env = validEnv();
            env.setProperty("app.cors.allowed-origins", "http://mytripsafar.com");
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("http://");
        }

        @Test
        @DisplayName("apex + www over https is accepted")
        void httpsOriginsAccepted() {
            MockEnvironment env = validEnv();
            env.setProperty("app.cors.allowed-origins", "https://mytripsafar.com,https://www.mytripsafar.com");
            assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("secrets that are public in this repository's git history")
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
        @DisplayName("identical staff and portal keys are rejected — they are the only realm boundary")
        void identicalRealmKeys() {
            MockEnvironment env = validEnv();
            env.setProperty("portal.jwt.secret", GOOD_JWT);   // same as jwt.secret
            assertThatThrownBy(() -> validator.validate(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IDENTICAL");
        }
    }

    @Test
    @DisplayName("every problem is collected into one message, not surfaced one boot at a time")
    void collectsAllProblems() {
        MockEnvironment env = validEnv();
        env.setProperty("superadmin.signup-secret", "CHANGE_ME");
        env.setProperty("SUPER_ADMIN_PASSWORD", "CHANGE_ME");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "create");
        env.setProperty("app.cors.allowed-origins", "http://mytripsafar.com");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("superadmin.signup-secret")
                        .contains("SUPER_ADMIN_PASSWORD")
                        .contains("DESTROYS")
                        .contains("http://"));
    }
}
