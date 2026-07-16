package com.crm.travelcrm.accounting.tds.enums;

/**
 * The Income-Tax section under which TDS is deducted when paying a vendor. The actual rate is
 * externalised per FY ({@code app.accounting.tds.*}); this enum only names the section and its config
 * key so the calculator can resolve the rate. Under Section 206AA a flat higher rate applies when the
 * vendor has furnished no PAN, overriding the section rate.
 */
public enum TdsSection {

    /** Payments to contractors (e.g. transport/ground handling). */
    SEC_194C("194C", "section-194c", "Contractor (194C)"),
    /** Commission or brokerage. */
    SEC_194H("194H", "section-194h", "Commission/Brokerage (194H)"),
    /** Professional / technical fees. */
    SEC_194J("194J", "section-194j", "Professional fees (194J)");

    private final String code;
    private final String configKey;
    private final String label;

    TdsSection(String code, String configKey, String label) {
        this.code = code;
        this.configKey = configKey;
        this.label = label;
    }

    public String code()      { return code; }
    public String configKey() { return configKey; }
    public String label()     { return label; }
}