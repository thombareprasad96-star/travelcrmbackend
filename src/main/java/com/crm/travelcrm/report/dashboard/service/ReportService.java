package com.crm.travelcrm.report.dashboard.service;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.report.activity.service.ActivityReportService;
import com.crm.travelcrm.report.booking.repository.BookingReportRepository;
import com.crm.travelcrm.report.booking.service.BookingRevenueService;
import com.crm.travelcrm.report.dashboard.dto.ReportSummaryDTO;
import com.crm.travelcrm.report.followup.service.FollowupReportService;
import com.crm.travelcrm.report.geographic.repository.GeoReportRepository;
import com.crm.travelcrm.report.geographic.service.GeographicReportService;
import com.crm.travelcrm.report.intldomestic.service.IntlDomesticService;
import com.crm.travelcrm.report.support.ReportDateRange;
import com.crm.travelcrm.report.support.ReportPeriod;
import com.crm.travelcrm.report.traveldate.service.TravelDateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Reports dashboard — the stat-card summary plus the "export all / export single" delegators.
 * Tenant-scoped via {@link TenantContext}; bare DTOs. PDF/XLSX are not generated — exports return
 * CSV bytes regardless of the requested {@code format} (the FE picks the download filename).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final long REPORT_TYPE_COUNT = 6;
    private static final DateTimeFormatter GENERATED_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy hh:mm a");

    private final UserRepository userRepository;
    private final GeoReportRepository geoReportRepository;
    private final BookingReportRepository bookingReportRepository;

    private final ActivityReportService activityReportService;
    private final GeographicReportService geographicReportService;
    private final FollowupReportService followupReportService;
    private final BookingRevenueService bookingRevenueService;
    private final TravelDateService travelDateService;
    private final IntlDomesticService intlDomesticService;

    @Transactional(readOnly = true)
    public ReportSummaryDTO getSummary(String period, String from, String to) {
        Long tenantId = requireTenant();
        String[] range = ReportPeriod.resolve(period, from, to);
        LocalDateTime[] dt = ReportDateRange.resolve(range[0], range[1]);

        long activeUsers = userRepository.countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(tenantId);

        List<Object[]> geo = geoReportRepository.summary(tenantId, dt[0], dt[1], null, null);
        long leads = !geo.isEmpty() && geo.get(0)[0] instanceof Number n ? n.longValue() : 0L;

        List<Booking> bookings = bookingReportRepository.findRevenueList(
                tenantId, "Booking Date", dt[0].toLocalDate(), dt[1].toLocalDate(), dt[0], dt[1],
                null, null, null, null, null);
        BigDecimal revenue = bookings.stream()
                .map(Booking::getCustomerAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ReportSummaryDTO.builder()
                .totalReports(REPORT_TYPE_COUNT)
                .activeUsers(activeUsers)
                .thisMonthLeads(leads)
                .revenueTracked(revenue)
                .period(period == null ? "month" : period)
                .generatedAt(LocalDateTime.now().format(GENERATED_FMT))
                .build();
    }

    /** Delegates to the matching per-report CSV export. {@code format} is ignored (CSV only). */
    @Transactional(readOnly = true)
    public byte[] exportSingle(String reportType, String period, String from, String to, String format) {
        String[] r = ReportPeriod.resolve(period, from, to);
        String start = r[0], end = r[1];
        return switch (reportType == null ? "" : reportType.toLowerCase()) {
            case "activity"               -> activityReportService.exportCsv(start, end, null, null, null);
            case "geographic"             -> geographicReportService.exportCsv(start, end, "Departing Cities", null, null, null);
            case "followup"               -> followupReportService.exportCsv("All", null, null, null, null, null);
            case "revenue"                -> bookingRevenueService.exportCsv(start, end, "Booking Date", null, null, null, null, null);
            case "travel-dates"           -> travelDateService.exportCsv(start, end, "Monthly", null, null);
            case "international-domestic"  -> intlDomesticService.exportCsv(start, end, "Booking Date", null);
            default -> ("Unknown report type: " + reportType).getBytes(StandardCharsets.UTF_8);
        };
    }

    /** "Export all" — a compact CSV of the dashboard summary. {@code format} is ignored (CSV only). */
    @Transactional(readOnly = true)
    public byte[] exportAll(String period, String from, String to, String format) {
        ReportSummaryDTO s = getSummary(period, from, to);
        StringBuilder csv = new StringBuilder();
        csv.append("Metric,Value\n");
        csv.append("Total Reports,").append(s.getTotalReports()).append('\n');
        csv.append("Active Users,").append(s.getActiveUsers()).append('\n');
        csv.append("Leads (period),").append(s.getThisMonthLeads()).append('\n');
        csv.append("Revenue Tracked,").append(s.getRevenueTracked()).append('\n');
        csv.append("Period,").append(s.getPeriod()).append('\n');
        csv.append("Generated At,").append(s.getGeneratedAt()).append('\n');
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read report summary.");
        }
        return tenantId;
    }
}