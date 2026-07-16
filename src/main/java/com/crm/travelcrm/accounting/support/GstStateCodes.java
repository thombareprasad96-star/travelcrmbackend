package com.crm.travelcrm.accounting.support;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves an Indian state/UT name to its 2-digit GST state code (the first two characters of a
 * GSTIN). Place-of-supply comparison — supplier state code vs recipient state code — decides whether
 * a supply is intra-state (CGST + SGST) or inter-state (IGST), so this mapping is the backbone of the
 * GST calculator.
 *
 * <p>{@code Company.state} / {@code Customer.state} are free-text names, so lookup is
 * case/whitespace-insensitive with a few common aliases. The supplier code is preferably derived
 * straight from the GSTIN via {@link #fromGstin(String)} (authoritative), falling back to the state
 * name only when no GSTIN is present.
 */
public final class GstStateCodes {

    private GstStateCodes() {}

    private static final Map<String, String> BY_NAME = buildNameMap();

    /** The 2-digit GST state code embedded in a GSTIN (chars 1–2), or {@code null} if not derivable. */
    public static String fromGstin(String gstin) {
        if (gstin == null) return null;
        String g = gstin.trim();
        if (g.length() < 2) return null;
        String code = g.substring(0, 2);
        return code.chars().allMatch(Character::isDigit) ? code : null;
    }

    /** The 2-digit GST state code for a state/UT name, or {@code null} if unrecognised. */
    public static String fromStateName(String state) {
        if (state == null) return null;
        return BY_NAME.get(normalize(state));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", " ");
    }

    private static Map<String, String> buildNameMap() {
        Map<String, String> m = new java.util.HashMap<>();
        put(m, "01", "jammu and kashmir", "j&k", "jammu & kashmir");
        put(m, "02", "himachal pradesh", "hp");
        put(m, "03", "punjab");
        put(m, "04", "chandigarh");
        put(m, "05", "uttarakhand", "uttaranchal");
        put(m, "06", "haryana");
        put(m, "07", "delhi", "new delhi", "nct of delhi");
        put(m, "08", "rajasthan");
        put(m, "09", "uttar pradesh", "up");
        put(m, "10", "bihar");
        put(m, "11", "sikkim");
        put(m, "12", "arunachal pradesh");
        put(m, "13", "nagaland");
        put(m, "14", "manipur");
        put(m, "15", "mizoram");
        put(m, "16", "tripura");
        put(m, "17", "meghalaya");
        put(m, "18", "assam");
        put(m, "19", "west bengal", "wb");
        put(m, "20", "jharkhand");
        put(m, "21", "odisha", "orissa");
        put(m, "22", "chhattisgarh", "chhatisgarh");
        put(m, "23", "madhya pradesh", "mp");
        put(m, "24", "gujarat");
        put(m, "26", "dadra and nagar haveli and daman and diu", "daman and diu", "dadra and nagar haveli");
        put(m, "27", "maharashtra");
        put(m, "29", "karnataka");
        put(m, "30", "goa");
        put(m, "31", "lakshadweep");
        put(m, "32", "kerala");
        put(m, "33", "tamil nadu", "tn");
        put(m, "34", "puducherry", "pondicherry");
        put(m, "35", "andaman and nicobar islands", "andaman and nicobar");
        put(m, "36", "telangana");
        put(m, "37", "andhra pradesh", "ap");
        put(m, "38", "ladakh");
        put(m, "97", "other territory");
        put(m, "96", "foreign country", "overseas", "outside india");
        return m;
    }

    private static void put(Map<String, String> m, String code, String... names) {
        for (String n : names) m.put(normalize(n), code);
    }
}