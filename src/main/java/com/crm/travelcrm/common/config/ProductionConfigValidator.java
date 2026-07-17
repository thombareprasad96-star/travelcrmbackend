package com.crm.travelcrm.common.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start the {@code prod} profile on an unsafe configuration.
 *
 * <p>Why this exists rather than trusting the properties files: every dev default in
 * {@code application.properties} has been in this repository's git history since the initial commit,
 * so all of them are public. {@code application-prod.properties} already declares each secret as
 * {@code ${VAR}} with no default, which catches a *missing* env var — but it cannot catch the far
 * likelier mistake of an env var that is *present and wrong*: someone copying the dev value into
 * {@code travelcrm.env} to "get it booting". A JWT signed with a published key is not authentication
 * at all; anyone could mint a token for any tenant. That failure is silent and total, so it is worth
 * a hard stop at boot.
 *
 * <p>Implemented as a {@link BeanFactoryPostProcessor} rather than a {@code @PostConstruct} bean so
 * that it runs <em>before</em> any regular singleton is instantiated — in particular before the
 * DataSource and Hibernate. As a {@code @PostConstruct} it fired only after Hibernate had already
 * opened the connection and run its {@code ddl-auto} DDL against the production database, which
 * defeats the point of a guard whose job is to stop before anything is touched. It also means the
 * "REFUSING TO START" block is the first thing in the log rather than buried under a JPA stack trace.
 *
 * <p>All violations are collected and reported together, so a deploy is not a guess-one-fix-one loop.
 */
@Component
@Profile("prod")
public class ProductionConfigValidator implements BeanFactoryPostProcessor {

    private static final Logger log = LogManager.getLogger(ProductionConfigValidator.class);

    /** The public dev values from application.properties. Present in prod ⇒ hard stop. */
    private static final String DEV_JWT_SECRET = "4Y7bN9vR+KzX2pQW5mE9JtGvC3sX8zK1rN6xP4vM1bA=";
    private static final String DEV_PORTAL_JWT_SECRET = "A474IqgP7X82N9xh/cOLKZgwoEHlSMsuOCRHAW50Jmk=";
    private static final String DEV_SIGNUP_SECRET = "mySecretKey123";
    private static final String DEV_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String DEV_DB_PASSWORD = "Admin123";

    /** HS256 needs a key at least as long as its output; jjwt rejects anything shorter anyway. */
    private static final int MIN_HMAC_KEY_BYTES = 32;

    /** The literals travelcrm.env.example ships with. Any of these reaching a boot means a missed line. */
    private static final Set<String> PLACEHOLDERS =
            Set.of("CHANGE_ME", "CHANGE_ME_DIFFERENT", "changeme", "TODO");

    /**
     * The Environment is fetched from the bean factory rather than injected: at
     * {@code BeanFactoryPostProcessor} time no {@code @Value} injection has happened yet, which is
     * precisely why this runs early enough to be useful. Spring registers the environment as a
     * singleton before post-processors are invoked, so this lookup is always safe here.
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        validate(beanFactory.getBean(Environment.class));
    }

    void validate(Environment env) {
        String jwtSecret = env.getProperty("jwt.secret");
        String portalJwtSecret = env.getProperty("portal.jwt.secret");
        String signupSecret = env.getProperty("superadmin.signup-secret");
        String encryptionKey = env.getProperty("app.encryption.key");
        String dbPassword = env.getProperty("spring.datasource.password");
        String publicBaseUrl = env.getProperty("app.public-base-url");
        String corsOrigins = env.getProperty("app.cors.allowed-origins");
        String superAdminPassword = env.getProperty("SUPER_ADMIN_PASSWORD");
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto");
        boolean seedEnabled = env.getProperty("app.seed.enabled", Boolean.class, false);

        List<String> problems = new ArrayList<>();

        rejectDevValue(problems, jwtSecret, DEV_JWT_SECRET, "jwt.secret", "JWT_SECRET");
        rejectDevValue(problems, portalJwtSecret, DEV_PORTAL_JWT_SECRET, "portal.jwt.secret", "PORTAL_JWT_SECRET");
        rejectDevValue(problems, signupSecret, DEV_SIGNUP_SECRET, "superadmin.signup-secret", "SUPERADMIN_SIGNUP_SECRET");
        rejectDevValue(problems, encryptionKey, DEV_ENCRYPTION_KEY, "app.encryption.key", "APP_ENCRYPTION_KEY");
        rejectDevValue(problems, dbPassword, DEV_DB_PASSWORD, "spring.datasource.password", "DB_PASSWORD");

        requireStrongBase64Key(problems, jwtSecret, "jwt.secret", "JWT_SECRET");
        requireStrongBase64Key(problems, portalJwtSecret, "portal.jwt.secret", "PORTAL_JWT_SECRET");

        // rejectDevValue only catches the one literal from application.properties. It says nothing
        // about blank or CHANGE_ME — the values an operator who copied travelcrm.env.example and
        // missed a line actually has. That matters most for the signup secret: /api/auth/** is
        // permitAll, and constantTimeEquals("", "") is MessageDigest.isEqual(byte[0], byte[0]),
        // which returns TRUE — so a blank secret authorizes a request that sends no header at all.
        requireConfigured(problems, signupSecret, "superadmin.signup-secret", "SUPERADMIN_SIGNUP_SECRET");
        requireConfigured(problems, encryptionKey, "app.encryption.key", "APP_ENCRYPTION_KEY");
        requireConfigured(problems, dbPassword, "spring.datasource.password", "DB_PASSWORD");

        // DataInitializer creates the platform SuperAdmin on the first boot against an empty
        // database. Without this the account is created with the repository's public fallback
        // password and sits on the internet-facing login form. DataInitializer throws on its own
        // too, but that happens after the context is up; caught here it joins the one problem block.
        requireConfigured(problems, superAdminPassword, "SUPER_ADMIN_PASSWORD", "SUPER_ADMIN_PASSWORD");

        // AesSecretCipher decodes this as Base64 and hands it to SecretKeySpec, so a non-Base64 or
        // wrong-length value boots past every check above and then dies at first use with
        // "Illegal base64 character" — a second failed boot, which is exactly what the
        // collect-every-problem design of this class exists to avoid. AES accepts 16/24/32 bytes;
        // requireStrongBase64Key's >=32 rule is wrong here and would reject a valid AES-128 key.
        requireAesKey(problems, encryptionKey, "app.encryption.key", "APP_ENCRYPTION_KEY");

        // The staff and traveler realms are kept apart ONLY by signing with different keys: a staff
        // token must fail signature validation on the portal chain and vice-versa. Identical keys
        // silently collapse the two realms into one, and a traveler token would start authenticating
        // against staff endpoints.
        if (jwtSecret != null && jwtSecret.equals(portalJwtSecret)) {
            problems.add("jwt.secret and portal.jwt.secret are IDENTICAL — this collapses the staff and "
                    + "traveler auth realms into one. Generate two different keys (openssl rand -base64 32).");
        }

        // The seeder plants a SuperAdmin with a hard-coded password. application-prod.properties pins
        // this to false; this catches a rogue override reaching the environment some other way.
        if (seedEnabled) {
            problems.add("app.seed.enabled is TRUE in prod — DevDataSeeder would create a SuperAdmin with a "
                    + "hard-coded password and demo data. It must be false.");
        }

        // Quotation share links and the Razorpay webhook URL are built from this. Left as localhost,
        // every link mailed to a real customer points at that customer's own machine.
        if (containsLocalhost(publicBaseUrl)) {
            problems.add("app.public-base-url is '" + publicBaseUrl + "' — set APP_PUBLIC_BASE_URL to the "
                    + "public https:// origin of this API.");
        }
        if (publicBaseUrl != null && publicBaseUrl.startsWith("http://")) {
            problems.add("app.public-base-url is plain http:// — customer-facing share links must be https://.");
        }

        // allowCredentials=true + "*" is rejected by every browser, and a localhost origin in prod
        // means the real frontend was never configured.
        if (corsOrigins == null || corsOrigins.isBlank()) {
            problems.add("app.cors.allowed-origins is empty — set APP_CORS_ALLOWED_ORIGINS to the frontend origin(s).");
        } else if (corsOrigins.contains("*")) {
            problems.add("app.cors.allowed-origins contains '*' — invalid with allowCredentials=true; list exact origins.");
        } else if (containsLocalhost(corsOrigins)) {
            problems.add("app.cors.allowed-origins still points at localhost ('" + corsOrigins + "').");
        } else if (corsOrigins.contains("http://")) {
            // The SPA is served over https, so the browser always sends Origin: https://... A plain
            // http:// origin here matches nothing and every API call fails CORS — while curl, Postman
            // and the actuator probe all keep working, because none of them send an Origin header.
            problems.add("app.cors.allowed-origins contains a plain http:// origin ('" + corsOrigins
                    + "') — the SPA is served over https, so its Origin will never match. Use https://.");
        }

        // The one destructive setting that is switchable from the environment: travelcrm.env.example
        // and DEPLOYMENT.md both invite editing JPA_DDL_AUTO. 'create'/'create-drop' drops and
        // recreates every table on each restart, and the boot stays green while doing it — the app
        // owns the tables, so indexes.sql and the enum validator both pass against the fresh schema.
        if (ddlAuto != null && !Set.of("update", "validate", "none").contains(ddlAuto.trim().toLowerCase())) {
            problems.add("spring.jpa.hibernate.ddl-auto is '" + ddlAuto + "' — 'create'/'create-drop' DESTROYS "
                    + "the production schema on every restart. JPA_DDL_AUTO must be update, validate or none.");
        }

        if (!problems.isEmpty()) {
            String detail = String.join(System.lineSeparator() + "  - ", problems);
            throw new IllegalStateException(String.join(System.lineSeparator(),
                    "",
                    "═══════════════════════════════════════════════════════════════════",
                    " REFUSING TO START: unsafe production configuration",
                    "═══════════════════════════════════════════════════════════════════",
                    "  - " + detail,
                    "",
                    "Fix these in the environment file (/etc/travelcrm/travelcrm.env) and restart.",
                    "See docs/DEPLOYMENT.md for how to generate each value.",
                    "═══════════════════════════════════════════════════════════════════"));
        }

        log.info("Production config validated: secrets are non-default, auth realms use distinct keys, "
                + "seeder off, public base URL and CORS origins set.");
    }

    private void rejectDevValue(List<String> problems, String actual, String devValue,
                                String property, String envVar) {
        if (devValue.equals(actual)) {
            problems.add(property + " is still the PUBLIC dev default (it is in git history). "
                    + "Set " + envVar + " to a fresh value.");
        }
    }

    /**
     * Reject the values an operator who copied travelcrm.env.example and missed a line actually
     * has: unset, blank, or the literal placeholder. {@link #rejectDevValue} only knows the one
     * dev literal, so none of these were caught before.
     */
    private void requireConfigured(List<String> problems, String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            problems.add(property + " is not set — add " + envVar + " to /etc/travelcrm/travelcrm.env.");
        } else if (PLACEHOLDERS.contains(value.trim())) {
            problems.add(property + " is still the placeholder '" + value.trim() + "' — set " + envVar
                    + " to a real value (openssl rand -base64 32).");
        }
    }

    /**
     * app.encryption.key is Base64-decoded straight into a SecretKeySpec by AesSecretCipher, so it
     * must be valid Base64 of an AES key length. Deliberately not requireStrongBase64Key: that
     * enforces >= 32 bytes, which would reject a perfectly valid AES-128 key.
     */
    private void requireAesKey(List<String> problems, String value, String property, String envVar) {
        if (value == null || value.isBlank() || PLACEHOLDERS.contains(value.trim())) {
            return;   // already reported by requireConfigured — don't say it twice
        }
        try {
            int length = Base64.getDecoder().decode(value).length;
            if (length != 16 && length != 24 && length != 32) {
                problems.add(property + " decodes to " + length + " bytes; AES needs 16, 24 or 32. "
                        + "Regenerate " + envVar + " with: openssl rand -base64 32");
            }
        } catch (IllegalArgumentException e) {
            problems.add(property + " is not valid Base64 — regenerate " + envVar
                    + " with: openssl rand -base64 32");
        }
    }

    private void requireStrongBase64Key(List<String> problems, String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            problems.add(property + " is empty — set " + envVar + ".");
            return;
        }
        try {
            int length = Base64.getDecoder().decode(value).length;
            if (length < MIN_HMAC_KEY_BYTES) {
                problems.add(property + " decodes to " + length + " bytes; HS256 needs at least "
                        + MIN_HMAC_KEY_BYTES + ". Generate one with: openssl rand -base64 32");
            }
        } catch (IllegalArgumentException e) {
            problems.add(property + " is not valid Base64 (" + envVar + "). Generate one with: openssl rand -base64 32");
        }
    }

    private boolean containsLocalhost(String value) {
        if (value == null) return false;
        String v = value.toLowerCase();
        return v.contains("localhost") || v.contains("127.0.0.1");
    }
}
