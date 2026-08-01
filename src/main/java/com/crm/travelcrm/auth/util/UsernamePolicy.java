package com.crm.travelcrm.auth.util;

import java.util.Locale;

/**
 * The single definition of what a tenant staff login username is.
 *
 * <p>Exists for the same reason every email writer lowercases: {@code uq_users_username_active} is a
 * case-SENSITIVE btree index, so "one username, one account" is a fiction unless every writer and
 * every reader agree on one canonical form. Login, the availability check, the uniqueness guard and
 * all four creation paths route through {@link #normalize(String)} so the value checked and the
 * value stored can never diverge by case or whitespace.
 *
 * <p>The charset is deliberately narrower than the email local-part it is usually derived from:
 * lowercase alphanumerics plus {@code . _ -}. No {@code @}, so a username can never be mistaken for
 * an address by a human reading an audit row (see {@code AuditingConfig} — {@code created_by} now
 * carries this value).
 */
public final class UsernamePolicy {

    public static final int MIN_LENGTH = 3;
    /** Matches {@code @Column(name = "username", length = 80)} on User — a mismatch is what
     *  {@code JPA_DDL_AUTO=validate} refuses to boot on. */
    public static final int MAX_LENGTH = 80;

    /**
     * Bean-validation pattern for inbound DTOs. Accepts uppercase so a user typing "Prasad" gets a
     * helpful normalization rather than a rejection; {@link #normalize} folds it to lowercase before
     * anything is checked or stored.
     */
    public static final String PATTERN = "^[A-Za-z0-9._-]+$";

    public static final String PATTERN_MESSAGE =
            "Username may contain only letters, digits, dot, underscore and hyphen";

    /**
     * Longest derived stem, leaving room for the {@code _<id>} disambiguator appended on collision.
     * Keep in step with the backfill in {@code V2__lead_code.sql}, which uses the same budget.
     */
    private static final int STEM_BUDGET = 60;

    private UsernamePolicy() {
    }

    /**
     * Canonical stored form: trimmed and lowercased. Returns {@code null} for null/blank input so
     * callers can treat "absent" and "present" distinctly rather than getting an empty string that
     * would silently pass a {@code != null} guard.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Best-effort username stem derived from an email address — the local-part, stripped to the
     * allowed charset. Used only where a username is not supplied by a human: the tenant-bootstrap
     * admin (a SuperAdmin creating the organization cannot know what login the customer wants) and
     * the dev seeder.
     *
     * <p>Deliberately mirrors the SQL backfill so a row created by the application and a row
     * repaired by the migration end up with the same shape. Never returns null — an address whose
     * local-part strips to nothing yields {@code "user"}, which the caller then disambiguates.
     */
    public static String stemFromEmail(String email) {
        String local = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int at = local.indexOf('@');
        if (at >= 0) {
            local = local.substring(0, at);
        }
        String stripped = local.replaceAll("[^a-z0-9._-]", "");
        if (stripped.isEmpty()) {
            return "user";
        }
        return stripped.length() > STEM_BUDGET ? stripped.substring(0, STEM_BUDGET) : stripped;
    }

    /**
     * First unused username of the form {@code stem}, {@code stem2}, {@code stem3}… as judged by
     * {@code taken}.
     *
     * <p>This is a convenience for the two paths that must invent a username rather than be given
     * one, and it is NOT a uniqueness guarantee: two concurrent creates can both clear the predicate
     * and race. {@code uq_users_username_active} is the actual guarantee — this only keeps the
     * common case from hitting it. Callers that take a username from a human must surface a
     * CONFLICT instead of silently renaming what the user typed.
     */
    public static String firstAvailable(String stem, java.util.function.Predicate<String> taken) {
        String base = normalize(stem) == null ? "user" : normalize(stem);
        String candidate = base;
        int suffix = 1;
        while (taken.test(candidate)) {
            candidate = base + (++suffix);
        }
        return candidate;
    }
}
