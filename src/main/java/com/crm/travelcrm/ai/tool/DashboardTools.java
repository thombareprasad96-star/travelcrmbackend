package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.report.dashboard.dto.ReportSummaryDTO;
import com.crm.travelcrm.report.dashboard.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Read-only dashboard tool. Delegates to the existing {@link ReportService#getSummary} (tenant-wide
 * counts) — no new aggregation logic.
 */
@Component
@RequiredArgsConstructor
public class DashboardTools {

    private final ReportService reportService;
    private final AiAuditService audit;

    public record DashboardCounts(long reportTypes, long activeUsers, long leadsInPeriod,
                                  String revenueTracked, String period, String generatedAt) {}

    @Tool(description = "Get high-level dashboard counts for the current tenant: active users, "
            + "leads in the period and revenue tracked.")
    public DashboardCounts getDashboardCounts(
            @ToolParam(required = false, description =
                    "Period: 'today', 'week', 'month' (default), 'year'") String period) {
        return audit.recordToolCall("getDashboardCounts", Map.of("period", ToolFmt.str(period)),
                () -> {
                    ReportSummaryDTO s = reportService.getSummary(period == null ? "month" : period, null, null);
                    return new DashboardCounts(
                            s.getTotalReports(), s.getActiveUsers(), s.getThisMonthLeads(),
                            ToolFmt.str(s.getRevenueTracked()), s.getPeriod(), s.getGeneratedAt());
                });
    }
}