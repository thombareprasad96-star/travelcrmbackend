package com.crm.travelcrm.quotation.enums;

/**
 * Which DESIGN a quotation is presented in — the PDF and the public weblink both key off it.
 *
 * <p><b>Not to be confused with {@code quotationtemplate.QuotationTemplate}</b>, which is a package
 * CONTENT template (pre-filled hotels/itinerary that seed the builder). That module answers "what is
 * in this quotation"; this enum answers "what does it look like". The two are unrelated, and the name
 * collision is why this type deliberately does not carry the word "Quotation".
 *
 * <p><b>Wire format is the plain enum name</b> ({@code "CLASSIC"}/{@code "MODERN"}), unlike
 * {@code QuotationStage}'s {@code @JsonValue} label: the frontend branches on it
 * ({@code data.templateStyle === 'MODERN'}) and a display label would put a human-facing string into a
 * machine comparison.
 *
 * <p><b>A null column means CLASSIC.</b> Existing rows predate the column ({@code ddl-auto=update}
 * adds it as NULL), and treating null as CLASSIC in code is what guarantees the zero-regression
 * promise: every quotation that exists today keeps rendering exactly as it always has.
 */
public enum TemplateStyle {

    /** The original design — the one every tenant has today. The default, forever. */
    CLASSIC,

    /** The premium editorial design (hero imagery, timeline itinerary). Opt-in per quotation. */
    MODERN,

    /**
     * The formal letterhead design — traditional travel-house document language (serif masthead,
     * oxblood/cream/gold), built to outclass {@link #MODERN} rather than replace {@link #CLASSIC}:
     * Classic stays exactly what long-standing customers already receive.
     *
     * <p>Adding a constant here is a TWO-PLACE change: {@code db/indexes.sql}'s
     * {@code quotations_template_style_check} must name it too, or
     * {@code SchemaEnumConstraintValidator} refuses to boot — by design.
     */
    PREMIUM,

    /**
     * The navy-and-gold coffee-table design — the only style NOT rendered by Flying Saucer/OpenPDF.
     * Its stylesheet uses CSS Grid, flexbox, {@code object-fit} and gradients, none of which that
     * engine understands, so it renders through headless Chromium instead
     * ({@code quotation.pdf.ChromiumLuxuryPdfRenderer}). Everything else — the DTO source, the
     * pricing, the tenant branding — is the same data the other three styles get.
     *
     * <p><b>It can be unavailable at runtime.</b> Chromium may be absent or
     * {@code pdf.luxury.enabled=false}; the renderer then answers with a clear error instead of
     * silently downgrading to another design. CLASSIC/MODERN/PREMIUM never touch that code path,
     * so they keep working regardless — see {@code QuotationPdfRouter}.
     */
    LUXURY;

    /** True for the styles the OpenPDF/Flying Saucer engine renders — i.e. everything but Luxury. */
    public boolean isLegacyEngine() {
        return this != LUXURY;
    }

    /** The rule "null means CLASSIC" written once, instead of at every call site. */
    public static TemplateStyle orDefault(TemplateStyle style) {
        return style == null ? CLASSIC : style;
    }
}
