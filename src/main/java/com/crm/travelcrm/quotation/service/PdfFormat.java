package com.crm.travelcrm.quotation.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Small formatting helper exposed to the Thymeleaf PDF template as {@code ${fmt}}.
 *
 * <p>Money is rendered as {@code "Rs. 1,23,456.00"} (Indian digit grouping) rather
 * than with the ₹ glyph: the OpenPDF base-14 fonts don't carry U+20B9, so a Unicode
 * rupee sign would render as a blank box unless a TTF is embedded. "Rs." is safe on
 * every system and standard on Indian travel documents.
 */
public class PdfFormat {

    private static final Locale INDIA = Locale.forLanguageTag("en-IN");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    public String inr(BigDecimal value) {
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(INDIA);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "Rs. " + nf.format(v);
    }

    public String date(LocalDate date) {
        return date == null ? "-" : date.format(DATE);
    }

    /**
     * Flattens rich-text HTML (the contentEditable output from the frontend notes
     * fields) into safe single-line plain text. Tags are stripped so nothing can
     * break the XML the renderer parses; lists/breaks become readable separators.
     */
    public String plain(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)<li[^>]*>", " - ")
                .replaceAll("(?i)</(p|div|li|ul|ol|h[1-6])>", " ")
                .replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Returns {@code value} if non-blank, otherwise {@code fallback}. */
    public String orElse(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** A short human description of the traveller mix, e.g. "4 Adults, 2 Children". */
    public String travellers(Integer adults, Integer children, Integer infants) {
        StringBuilder sb = new StringBuilder();
        append(sb, adults, "Adult");
        append(sb, children, "Child");
        append(sb, infants, "Infant");
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void append(StringBuilder sb, Integer count, String noun) {
        if (count == null || count <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        String plural = count == 1 ? noun : (noun.equals("Child") ? "Children" : noun + "s");
        sb.append(count).append(' ').append(plural);
    }
}