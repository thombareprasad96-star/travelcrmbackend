package com.crm.travelcrm.report.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Resolves the dashboard {@code period} keyword (today | week | month | quarter | year | custom)
 * into a concrete {@code [startDate, endDate]} pair of ISO {@code yyyy-MM-dd} strings, so the
 * dashboard endpoints can reuse the same date-range plumbing the per-report endpoints use.
 */
public final class ReportPeriod {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private ReportPeriod() {}

    public static String[] resolve(String period, String from, String to) {
        LocalDate today = LocalDate.now();
        String p = period == null ? "month" : period.trim().toLowerCase();
        return switch (p) {
            case "today"   -> new String[]{ ISO.format(today), ISO.format(today) };
            case "week"    -> new String[]{ ISO.format(today.with(DayOfWeek.MONDAY)), ISO.format(today) };
            case "quarter" -> {
                int q = (today.getMonthValue() - 1) / 3;
                LocalDate start = today.withMonth(q * 3 + 1).withDayOfMonth(1);
                yield new String[]{ ISO.format(start), ISO.format(today) };
            }
            case "year"    -> new String[]{ ISO.format(today.withDayOfYear(1)), ISO.format(today) };
            case "custom"  -> new String[]{
                    (from != null && !from.isBlank()) ? from.trim() : ISO.format(today.withDayOfMonth(1)),
                    (to   != null && !to.isBlank())   ? to.trim()   : ISO.format(today) };
            default        -> new String[]{ ISO.format(today.withDayOfMonth(1)), ISO.format(today) }; // month
        };
    }
}
