package com.crm.travelcrm.fleet.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatting helper exposed to the fleet PDF templates as {@code ${fmt}}.
 *
 * <p><b>Why this is not {@code quotation.service.PdfFormat}.</b> Fleet ships as an independent
 * product, and {@code quotation} is one of the CRM modules a Fleet-only deployment does not
 * contain — importing that class here would be caught by {@code FleetBoundaryArchTest}, and
 * rightly so. The overlap is three trivial methods; the alternative (hoisting a shared type into
 * {@code common}) would drag {@code travellers(adults, children, infants)} along, which is
 * quotation vocabulary that means nothing on a duty slip.
 *
 * <p>Money renders as {@code "Rs. 1,23,456.00"}, not with the ₹ glyph: the OpenPDF base-14 fonts
 * carry no U+20B9, so a Unicode rupee prints as a blank box. "Rs." is standard on Indian travel
 * and transport paperwork anyway.
 */
public class FleetPdfFormat {

    private static final Locale INDIA = Locale.forLanguageTag("en-IN");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    public String inr(BigDecimal value) {
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(INDIA);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "Rs. " + nf.format(v);
    }

    public String date(LocalDate date) {
        return date == null ? "—" : date.format(DATE);
    }

    public String dateTime(LocalDateTime at) {
        return at == null ? "—" : at.format(DATETIME);
    }

    /** Odometer / distance. A blank rather than a zero when unknown: the driver writes it in. */
    public String km(Integer value) {
        if (value == null) return "";
        return java.text.NumberFormat.getIntegerInstance(INDIA).format(value);
    }

    /** "1 day 4h 20m" — how long the vehicle was out, which is what extra-hours billing argues over. */
    public String duration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) return "—";
        Duration d = Duration.between(from, to);
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(days == 1 ? " day " : " days ");
        if (hours > 0) sb.append(hours).append("h ");
        sb.append(minutes).append('m');
        return sb.toString().trim();
    }

    public String orElse(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Absolute value, for a sheet that prints the direction as a word instead of a minus sign. */
    public String abs(BigDecimal value) {
        return inr(value == null ? BigDecimal.ZERO : value.abs());
    }
}
