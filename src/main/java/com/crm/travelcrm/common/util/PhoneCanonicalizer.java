package com.crm.travelcrm.common.util;

/**
 * Derives the E.164-canonical form of a phone number — the value stored in
 * {@code leads.phone_normalized}.
 *
 * <p><b>This is NOT {@link PhoneNormalizer}, and the two must not be merged.</b> {@code PhoneNormalizer}
 * is {@code trim()}-only <em>by deliberate design</em>: its javadoc records that rewriting the stored
 * format "would silently break matches against existing rows", and {@code Customer.phone} /
 * {@code uq_customers_phone_tenant} key off those exact strings. It guards the RAW column and stays
 * conservative. This class derives a SHADOW column and is free to be aggressive, because nothing
 * historical is keyed on its output.
 *
 * <p><b>Why a shadow column at all.</b> Lead dedup compares the phone RAW
 * ({@code LeadServiceImpl.validateNoDuplicates}). {@code DevDataSeeder} writes
 * {@code "+91 91234 5000" + i} — with spaces — so real rows carry separators. Inbound channels
 * deliver bare E.164. {@code "+919123450001"} never string-matches {@code "+91 91234 50001"}, so the
 * append-on-repeat behaviour would never fire and every repeat caller would silently become a
 * duplicate lead. No error, no log.
 *
 * <p><b>This is the third phone treatment in the codebase and deliberately the last.</b>
 * {@code WhatsAppMessagingService.normalize} (`:146-156`) already does E.164 for outbound sends; its
 * logic is reproduced here rather than shared because that method is private to a module with a
 * different job (addressing a message vs. keying a row) and coupling them would mean a
 * WhatsApp-provider change could silently repartition lead identity. Adapters must NOT canonicalise —
 * sixteen adapters each doing it differently is precisely the failure being prevented.
 *
 * <p><b>Pure and static on purpose.</b> The one-time backfill reuses this exact method rather than a
 * SQL reimplementation: a SQL version that disagrees on one edge case <em>is</em> the migration risk.
 */
public final class PhoneCanonicalizer {

    private PhoneCanonicalizer() {
    }

    /**
     * The national-number length for the default country. India is fixed at 10 digits, which is what
     * makes {@link #canonical} able to tell {@code "919812345678"} (country code already present)
     * from {@code "9198765432"} (a national number that merely starts with 91) without guessing.
     *
     * <p><b>This is the India assumption, stated once, here.</b> The heuristic below is only sound
     * for a fixed-length numbering plan. If this CRM ever serves a country with variable-length
     * national numbers, do NOT widen the heuristic — adopt libphonenumber. Guessing at variable
     * lengths silently maps two different people onto one key, and the failure is invisible.
     */
    private static final int NATIONAL_DIGITS = 10;

    /**
     * E.164-canonical phone, or {@code null} when the input is null/blank, has no digits, or cannot
     * be canonicalised without guessing.
     *
     * <p><b>Null is a legitimate result and callers must handle it.</b> The column is nullable, the
     * lookup index is partial ({@code WHERE phone_normalized IS NOT NULL}), and a null must never be
     * treated as a match — otherwise every unparseable-phone lead would dedup against every other
     * one. Refusing to canonicalise is always safer than canonicalising wrongly: an un-matched lead
     * is a duplicate row someone can merge, a mis-matched one appends a stranger's enquiry to
     * another customer's lead.
     *
     * @param raw         the phone as typed or as delivered; may contain spaces, dashes, brackets
     * @param countryHint E.164 country prefix ("+91"), applied only when {@code raw} carries no "+"
     *                    and no already-present country code. A null/blank hint on a national-format
     *                    number yields {@code null} rather than a guess — a wrong country code is a
     *                    wrong person.
     */
    public static String canonical(String raw, String countryHint) {
        if (raw == null) return null;

        // A '+' counts only if it genuinely leads; mid-string it is noise.
        String trimmed = raw.trim();
        boolean explicitlyInternational = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;

        if (explicitlyInternational) {
            return "+" + digits;
        }

        // A leading trunk zero ("09812345678") is a dialling prefix, not part of E.164.
        String national = digits.replaceFirst("^0+", "");
        if (national.isEmpty()) return null;

        String cc = countryHint == null ? "" : countryHint.trim().replaceAll("[^0-9]", "");
        if (cc.isEmpty()) return null;

        // The '+'-less country-code case: "919812345678". Marketplace webhooks send this routinely.
        // Prepending the hint blindly would yield "+91919812345678" — a number that matches nothing
        // and is wrong in a way no test with a "+" in it would ever catch. Only accept the prefix as
        // a country code when what follows it is exactly a full national number, so a genuine
        // national number that happens to start with the country-code digits ("9198765432") is not
        // mistaken for one.
        if (national.length() == cc.length() + NATIONAL_DIGITS && national.startsWith(cc)) {
            return "+" + national;
        }

        if (national.length() != NATIONAL_DIGITS) {
            // Neither a national number nor a recognisable country-code-prefixed one. Could be a
            // short code, an extension-laden string, or a foreign number typed without its '+'.
            // Any of those would be a guess. Return null and let it stay un-canonicalised.
            return null;
        }

        return "+" + cc + national;
    }
}
