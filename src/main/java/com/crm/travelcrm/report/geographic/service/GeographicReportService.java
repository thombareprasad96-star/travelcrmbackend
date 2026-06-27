package com.crm.travelcrm.report.geographic.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.report.geographic.dto.GeoDistributionResponseDTO;
import com.crm.travelcrm.report.geographic.dto.GeoRowDTO;
import com.crm.travelcrm.report.geographic.dto.GeoSummaryDTO;
import com.crm.travelcrm.report.geographic.repository.GeoReportRepository;
import com.crm.travelcrm.report.support.ReportDateRange;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the Geographic Distribution report. Groups leads by departing city (default) or
 * country; tenant-scoped via {@link TenantContext}. Bare DTOs per the reports contract.
 *
 * <p>Note: the Lead model has no temperature (Hot/Warm/Cold) or dedicated destination/state column,
 * so those buckets are 0 and "Destinations"/"States" views fall back to the departing-city grouping.
 */
@Service
@RequiredArgsConstructor
public class GeographicReportService {

    private final GeoReportRepository geoReportRepository;

    @Transactional(readOnly = true)
    public GeoDistributionResponseDTO getData(String startDate, String endDate, String viewType,
                                              String leadType, String leadStage, String search,
                                              int perPage, int page) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        LeadType type = parseLeadType(leadType);
        LeadStage stage = parseLeadStage(leadStage);
        String searchParam = blankToNull(search);

        boolean byCountry = "Countries".equalsIgnoreCase(viewType) || "States".equalsIgnoreCase(viewType);
        List<Object[]> raw = byCountry
                ? geoReportRepository.aggregateByCountry(tenantId, range[0], range[1], type, stage, searchParam)
                : geoReportRepository.aggregateByCity(tenantId, range[0], range[1], type, stage, searchParam);

        long grandTotal = raw.stream().mapToLong(r -> toLong(r[2])).sum();
        List<GeoRowDTO> all = new ArrayList<>(raw.size());
        int index = 1;
        for (Object[] r : raw) {
            long total = toLong(r[2]);
            long fresh = toLong(r[3]);
            long converted = toLong(r[4]);
            all.add(GeoRowDTO.builder()
                    .id(index++)
                    .city(r[0] != null ? r[0].toString() : "Unknown")
                    .country(r[1] != null ? r[1].toString() : null)
                    .total(total)
                    .hot(0).warm(0).cold(0)
                    .fresh(fresh)
                    .converted(converted)
                    .conversionRate(pct(converted, total))
                    .distribution(pct(total, grandTotal))
                    .build());
        }

        int safePage    = Math.max(page, 1);
        int safePerPage = Math.min(Math.max(perPage, 1), 500);
        int fromIdx = Math.min((safePage - 1) * safePerPage, all.size());
        int toIdx   = Math.min(fromIdx + safePerPage, all.size());
        List<GeoRowDTO> pageRows = all.subList(fromIdx, toIdx);

        return GeoDistributionResponseDTO.builder()
                .rows(pageRows)
                .total(all.size())
                .page(safePage)
                .perPage(safePerPage)
                .totalPages((int) Math.ceil((double) all.size() / safePerPage))
                .build();
    }

    @Transactional(readOnly = true)
    public GeoSummaryDTO getSummary(String startDate, String endDate, String leadType, String leadStage) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        List<Object[]> rows = geoReportRepository.summary(
                tenantId, range[0], range[1], parseLeadType(leadType), parseLeadStage(leadStage));
        Object[] r = rows.isEmpty() ? new Object[]{0L, 0L, 0L} : rows.get(0);
        return GeoSummaryDTO.builder()
                .totalLeads(toLong(r[0]))
                .hotLeads(0).warmLeads(0).coldLeads(0)
                .freshLeads(toLong(r[1]))
                .converted(toLong(r[2]))
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, String viewType,
                            String leadType, String leadStage, String search) {
        // Reuse getData with a large page to get every grouped row.
        GeoDistributionResponseDTO data = getData(startDate, endDate, viewType, leadType, leadStage, search, Integer.MAX_VALUE, 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("City", "Country", "Total Leads", "Hot", "Warm", "Cold",
                    "Fresh", "Converted", "Conversion Rate", "Distribution");
            for (GeoRowDTO r : data.getRows()) {
                printer.printRecord(r.getCity(), r.getCountry(), r.getTotal(), r.getHot(), r.getWarm(),
                        r.getCold(), r.getFresh(), r.getConverted(),
                        r.getConversionRate() + "%", r.getDistribution() + "%");
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate geographic CSV export", e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read geographic reports.");
        }
        return tenantId;
    }

    private static double pct(long part, long whole) {
        if (whole <= 0) return 0.0;
        return Math.round((double) part / whole * 1000) / 10.0;
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static LeadType parseLeadType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LeadType.fromValue(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static LeadStage parseLeadStage(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LeadStage.fromValue(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}