package com.crm.travelcrm.lead.bulkimport;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The spreadsheet contract for bulk lead import — <b>one source of truth</b> for the header the
 * parser looks for, the template the tenant downloads, and the "unknown column" / "missing column"
 * messages. Adding a supported column is a one-line change here and a mapping line in
 * {@link LeadImportRowMapper}; nothing else needs touching.
 *
 * <p><b>Header matching is deliberately forgiving</b> ({@link #normalize}): case, surrounding
 * whitespace, underscores and non-alphanumerics are all ignored, so {@code "Customer Name"},
 * {@code "customer_name"} and {@code "CUSTOMER NAME "} are the same column. A real agency's
 * spreadsheet has been through several hands; rejecting it over a capital letter would send the user
 * straight back to the manual form, which is the outcome this feature exists to avoid.
 *
 * <p>{@code required} means "the import cannot invent this". Lead Stage is intentionally NOT required
 * even though {@code CreateLeadRequestDto} marks it {@code @NotNull} — a lead being imported is a new
 * lead, so the mapper defaults a blank cell to {@code New Lead} rather than failing the row.
 */
public enum LeadImportColumn {

    // ── Required ─────────────────────────────────────────────────────────────
    CUSTOMER_NAME("Customer Name", true, "Ravi Sharma"),
    /** Must satisfy the same {@code @Pattern} the API enforces: optional +, then 8-15 digits. */
    PHONE("Phone", true, "+919876543210"),
    LEAD_SOURCE("Lead Source", true, "Website"),
    LEAD_TYPE("Lead Type", true, "Fresh"),

    // ── Optional ─────────────────────────────────────────────────────────────
    EMAIL("Email", false, "ravi.sharma@example.com"),
    /** Blank defaults to "New Lead". */
    LEAD_STAGE("Lead Stage", false, "New Lead"),
    /**
     * The owner's <b>username</b>, not their name or email. Username is the unique login identity
     * (email is duplicable — a shared office mailbox is legitimate), so it is the only field that
     * resolves to exactly one user. Blank means "let the system decide": an admin/manager gets the
     * load-balanced recommendation, everyone else is assigned to themselves.
     */
    ASSIGNED_TO("Assigned To (username)", false, "priya.nair"),
    BUDGET("Budget", false, "85000"),
    TRAVEL_DATE("Travel Date", false, "2026-11-14"),
    FOLLOW_UP_DATE("Follow Up Date", false, "2026-08-20"),
    PACKAGE_TYPE("Package Type", false, "Honeymoon"),
    DEPART_COUNTRY("Depart Country", false, "India"),
    DEPART_CITY("Depart City", false, "Mumbai"),
    ADULTS("Adults", false, "2"),
    CHILDREN("Children", false, "1"),
    INFANTS("Infants", false, "0"),
    ROOMS("Rooms", false, "1"),
    /** Semicolon-separated, e.g. {@code Hotel;Flight;Visa}. Commas would fight the CSV itself. */
    SERVICES("Services", false, "Hotel;Flight"),
    PREFERRED_COMMUNICATION("Preferred Communication", false, "WhatsApp"),
    BIRTH_DATE("Birth Date", false, "1990-04-02"),
    ANNIVERSARY_DATE("Anniversary Date", false, "2015-12-01"),
    NOTES("Notes", false, "Prefers evening flights");

    private final String header;
    private final boolean required;
    private final String example;

    LeadImportColumn(String header, boolean required, String example) {
        this.header = header;
        this.required = required;
        this.example = example;
    }

    public String getHeader() {
        return header;
    }

    public boolean isRequired() {
        return required;
    }

    public String getExample() {
        return example;
    }

    /**
     * Strip everything that varies between two people typing the same header. Applied to BOTH the
     * enum's own header and the incoming one, so the comparison is symmetric.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Resolve an incoming spreadsheet header to a known column, if it is one. */
    public static Optional<LeadImportColumn> fromHeader(String raw) {
        String key = normalize(raw);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(c -> normalize(c.header).equals(key))
                .findFirst();
    }
}
