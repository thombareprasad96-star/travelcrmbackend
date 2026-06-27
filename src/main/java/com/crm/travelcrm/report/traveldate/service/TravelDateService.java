package com.crm.travelcrm.report.traveldate.service;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.report.booking.repository.BookingReportRepository;
import com.crm.travelcrm.report.support.ReportDateRange;
import com.crm.travelcrm.report.traveldate.dto.DurationRangeDTO;
import com.crm.travelcrm.report.traveldate.dto.PeakDateDTO;
import com.crm.travelcrm.report.traveldate.dto.PeriodAnalysisResponseDTO;
import com.crm.travelcrm.report.traveldate.dto.PeriodRowDTO;
import com.crm.travelcrm.report.traveldate.dto.TravelSummaryDTO;
import com.crm.travelcrm.report.traveldate.dto.TrendDataDTO;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the Travel-date Analysis report. Groups bookings by travel-date period (Monthly /
 * Weekly / Daily / Quarterly via Postgres {@code TO_CHAR}). Tenant-scoped; bare DTOs. The Booking
 * entity has no traveler count or trip duration, so travelers / avgDuration are 0 and the duration
 * breakdown is empty. {@code bookingType} (trip type) has no column and is ignored.
 */
@Service
@RequiredArgsConstructor
public class TravelDateService {

    private static final DateTimeFormatter PEAK_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy");

    private final BookingReportRepository repository;

    @Transactional(readOnly = true)
    public TravelSummaryDTO getSummary(String startDate, String endDate, String bookingType, String status) {
        List<TrendDataDTO> trends = trends(startDate, endDate, "Monthly", status);
        long totalBookings = trends.stream().mapToLong(TrendDataDTO::getBookings).sum();
        BigDecimal totalRevenue = trends.stream()
                .map(TrendDataDTO::getRevenue).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double avgPerPeriod = trends.isEmpty() ? 0.0
                : Math.round((double) totalBookings / trends.size() * 10) / 10.0;
        return TravelSummaryDTO.builder()
                .totalBookings(totalBookings)
                .totalTravelers(0)
                .avgPerPeriod(avgPerPeriod)
                .totalRevenue(totalRevenue)
                .build();
    }

    @Transactional(readOnly = true)
    public List<TrendDataDTO> getTrends(String startDate, String endDate, String analysisType,
                                        String bookingType, String status) {
        return trends(startDate, endDate, analysisType, status);
    }

    @Transactional(readOnly = true)
    public List<PeakDateDTO> getPeakDates(String startDate, String endDate, String bookingType,
                                          String status, int topN) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        List<Object[]> raw = repository.peakDates(
                tenantId, range[0].toLocalDate(), range[1].toLocalDate(), statusName(status), Math.max(topN, 1));
        List<PeakDateDTO> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            out.add(PeakDateDTO.builder()
                    .date(toLocalDate(r[0]) != null ? toLocalDate(r[0]).format(PEAK_FMT) : null)
                    .bookings(toLong(r[1]))
                    .label("Peak")
                    .build());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PeriodAnalysisResponseDTO getAnalysis(String startDate, String endDate, String analysisType,
                                                 String bookingType, String status, int perPage, int page) {
        List<TrendDataDTO> trends = trends(startDate, endDate, analysisType, status);
        BigDecimal totalRevenue = trends.stream()
                .map(TrendDataDTO::getRevenue).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PeriodRowDTO> all = new ArrayList<>(trends.size());
        for (TrendDataDTO t : trends) {
            double pct = totalRevenue.signum() > 0 && t.getRevenue() != null
                    ? t.getRevenue().divide(totalRevenue, 4, java.math.RoundingMode.HALF_UP)
                        .movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue()
                    : 0.0;
            all.add(PeriodRowDTO.builder()
                    .month(t.getMonth())
                    .bookings(t.getBookings())
                    .travelers(t.getTravelers())
                    .revenue(t.getRevenue())
                    .avgDuration(t.getAvgDuration())
                    .pctOfTotal(pct)
                    .build());
        }

        int safePage    = Math.max(page, 1);
        int safePerPage = Math.min(Math.max(perPage, 1), 500);
        int fromIdx = Math.min((safePage - 1) * safePerPage, all.size());
        int toIdx   = Math.min(fromIdx + safePerPage, all.size());
        return PeriodAnalysisResponseDTO.builder()
                .rows(all.subList(fromIdx, toIdx))
                .total(all.size())
                .page(safePage)
                .perPage(safePerPage)
                .totalPages((int) Math.ceil((double) all.size() / safePerPage))
                .build();
    }

    @Transactional(readOnly = true)
    public List<DurationRangeDTO> getDuration(String startDate, String endDate, String bookingType, String status) {
        // No trip-duration column on Booking → no breakdown available.
        return List.of();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, String analysisType, String bookingType, String status) {
        List<TrendDataDTO> trends = trends(startDate, endDate, analysisType, status);
        BigDecimal totalRevenue = trends.stream()
                .map(TrendDataDTO::getRevenue).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Month/Period", "Total Bookings", "Total Travelers",
                    "Revenue (INR)", "Avg Duration (days)", "% of Total");
            for (TrendDataDTO t : trends) {
                double pct = totalRevenue.signum() > 0 && t.getRevenue() != null
                        ? t.getRevenue().divide(totalRevenue, 4, java.math.RoundingMode.HALF_UP)
                            .movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue()
                        : 0.0;
                printer.printRecord(t.getMonth(), t.getBookings(), t.getTravelers(),
                        t.getRevenue(), t.getAvgDuration(), pct + "%");
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate travel-date CSV export", e);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private List<TrendDataDTO> trends(String startDate, String endDate, String analysisType, String status) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        List<Object[]> raw = repository.travelTrends(
                tenantId, range[0].toLocalDate(), range[1].toLocalDate(), resolveFmt(analysisType), statusName(status));
        List<TrendDataDTO> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            out.add(TrendDataDTO.builder()
                    .month(r[0] != null ? r[0].toString() : null)
                    .bookings(toLong(r[1]))
                    .travelers(0)
                    .revenue(toBigDecimal(r[2]))
                    .avgDuration(0)
                    .build());
        }
        return out;
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read travel-date reports.");
        }
        return tenantId;
    }

    private static String resolveFmt(String analysisType) {
        if (analysisType == null) return "Mon YYYY";
        return switch (analysisType) {
            case "Weekly"    -> "IW/IYYY";
            case "Daily"     -> "YYYY-MM-DD";
            case "Quarterly" -> "\"Q\"Q YYYY";
            default          -> "Mon YYYY";
        };
    }

    /** FE status label ("Confirmed") → enum name ("CONFIRMED") for the native query, or null. */
    private static String statusName(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return BookingStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate ld) return ld;
        return null;
    }
}