package com.crm.travelcrm.report.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Resolves the {@code startDate}/{@code endDate} query strings the report pages send (ISO
 * {@code yyyy-MM-dd}, both optional) into an inclusive {@code [from, to]} {@link LocalDateTime}
 * range. Mirrors the default the frontend services document: missing {@code startDate} ⇒ last 30
 * days, missing {@code endDate} ⇒ now.
 */
public final class ReportDateRange {

    private ReportDateRange() {}

    public static LocalDateTime[] resolve(String startDate, String endDate) {
        LocalDateTime from = (startDate != null && !startDate.isBlank())
                ? LocalDate.parse(startDate.trim()).atStartOfDay()
                : LocalDateTime.now().minusDays(30);
        LocalDateTime to = (endDate != null && !endDate.isBlank())
                ? LocalDate.parse(endDate.trim()).atTime(LocalTime.MAX)
                : LocalDateTime.now();
        return new LocalDateTime[]{ from, to };
    }
}