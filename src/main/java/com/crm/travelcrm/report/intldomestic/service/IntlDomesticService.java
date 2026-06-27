package com.crm.travelcrm.report.intldomestic.service;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.report.booking.repository.BookingReportRepository;
import com.crm.travelcrm.report.intldomestic.dto.DestinationDTO;
import com.crm.travelcrm.report.intldomestic.dto.DistributionDTO;
import com.crm.travelcrm.report.intldomestic.dto.IntlDomesticResponseDTO;
import com.crm.travelcrm.report.intldomestic.dto.TripTypeDataDTO;
import com.crm.travelcrm.report.support.ReportDateRange;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the International vs Domestic report. The Booking entity has no trip-type column, so
 * every booking is treated as <b>Domestic</b> (International is always empty/zero). Tenant-scoped;
 * bare DTOs. Top destinations are grouped by the booking's destination snapshot.
 */
@Service
@RequiredArgsConstructor
public class IntlDomesticService {

    private static final String INTERNATIONAL = "International";

    private final BookingReportRepository repository;

    @Transactional(readOnly = true)
    public TripTypeDataDTO getByTripType(String tripType, String startDate, String endDate,
                                         String dateType, String status) {
        if (INTERNATIONAL.equalsIgnoreCase(tripType)) {
            return emptyPanel();
        }
        return buildDomestic(domesticBookings(startDate, endDate, dateType, status), 5);
    }

    @Transactional(readOnly = true)
    public IntlDomesticResponseDTO getAll(String startDate, String endDate, String dateType, String status) {
        TripTypeDataDTO intl = emptyPanel();
        TripTypeDataDTO dom  = buildDomestic(domesticBookings(startDate, endDate, dateType, status), 5);
        return IntlDomesticResponseDTO.builder()
                .international(intl)
                .domestic(dom)
                .distribution(computeDistribution(intl, dom))
                .build();
    }

    @Transactional(readOnly = true)
    public DistributionDTO getDistribution(String startDate, String endDate, String dateType, String status) {
        TripTypeDataDTO intl = emptyPanel();
        TripTypeDataDTO dom  = buildDomestic(domesticBookings(startDate, endDate, dateType, status), 5);
        return computeDistribution(intl, dom);
    }

    @Transactional(readOnly = true)
    public List<DestinationDTO> getTopDestinations(String tripType, String startDate, String endDate,
                                                   String dateType, String status, int topN) {
        if (INTERNATIONAL.equalsIgnoreCase(tripType)) {
            return List.of();
        }
        return topDestinations(domesticBookings(startDate, endDate, dateType, status), topN);
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, String dateType, String status) {
        TripTypeDataDTO intl = emptyPanel();
        TripTypeDataDTO dom  = buildDomestic(domesticBookings(startDate, endDate, dateType, status), 5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Type", "Total Revenue", "Total Bookings", "Avg Value", "Avg Nights", "TCS", "Growth %");
            printer.printRecord("International", intl.getTotalRevenue(), intl.getTotalBookings(),
                    intl.getAvgValue(), intl.getAvgNights(), intl.getTcs(), intl.getGrowthPct() + "%");
            printer.printRecord("Domestic", dom.getTotalRevenue(), dom.getTotalBookings(),
                    dom.getAvgValue(), dom.getAvgNights(), dom.getTcs(), dom.getGrowthPct() + "%");
            printer.println();
            printer.printRecord("Top Domestic Destinations");
            printer.printRecord("Destination", "Country", "Bookings", "Revenue");
            for (DestinationDTO d : dom.getDestinations()) {
                printer.printRecord(d.getName(), d.getCountry(), d.getBookings(), d.getRevenue());
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate intl/domestic CSV export", e);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private List<Booking> domesticBookings(String startDate, String endDate, String dateType, String status) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        return repository.findRevenueList(
                tenantId,
                dateType == null || dateType.isBlank() ? "Booking Date" : dateType,
                range[0].toLocalDate(), range[1].toLocalDate(), range[0], range[1],
                parseStatus(status), null, null, null, null);
    }

    private TripTypeDataDTO buildDomestic(List<Booking> bookings, int topN) {
        BigDecimal totalRevenue = sum(bookings, Booking::getCustomerAmount);
        BigDecimal tcs          = sum(bookings, Booking::getTcs);
        long count = bookings.size();
        BigDecimal avgValue = count > 0
                ? totalRevenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return TripTypeDataDTO.builder()
                .totalRevenue(totalRevenue)
                .totalBookings(count)
                .avgValue(avgValue)
                .avgNights(0.0)
                .tcs(tcs)
                .growthPct(0.0)
                .destinations(topDestinations(bookings, topN))
                .build();
    }

    private List<DestinationDTO> topDestinations(List<Booking> bookings, int topN) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> revenues = new LinkedHashMap<>();
        for (Booking b : bookings) {
            String dest = b.getDestinationSnapshot() != null ? b.getDestinationSnapshot() : "Unknown";
            counts.computeIfAbsent(dest, k -> new long[1])[0]++;
            revenues.merge(dest, b.getCustomerAmount() != null ? b.getCustomerAmount() : BigDecimal.ZERO, BigDecimal::add);
        }
        List<DestinationDTO> list = new ArrayList<>(counts.size());
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            list.add(DestinationDTO.builder()
                    .name(e.getKey())
                    .country(null)
                    .bookings(e.getValue()[0])
                    .revenue(revenues.getOrDefault(e.getKey(), BigDecimal.ZERO))
                    .build());
        }
        list.sort(Comparator.comparingLong(DestinationDTO::getBookings).reversed()
                .thenComparing(d -> d.getRevenue(), Comparator.reverseOrder()));
        return list.size() > topN ? new ArrayList<>(list.subList(0, Math.max(topN, 0))) : list;
    }

    private DistributionDTO computeDistribution(TripTypeDataDTO intl, TripTypeDataDTO dom) {
        BigDecimal totRev = nz(intl.getTotalRevenue()).add(nz(dom.getTotalRevenue()));
        long totBkg = intl.getTotalBookings() + dom.getTotalBookings();
        int intlRevPct = totRev.signum() > 0
                ? nz(intl.getTotalRevenue()).multiply(BigDecimal.valueOf(100))
                    .divide(totRev, 0, RoundingMode.HALF_UP).intValue() : 0;
        int intlBkgPct = totBkg > 0 ? (int) Math.round((double) intl.getTotalBookings() / totBkg * 100) : 0;
        return DistributionDTO.builder()
                .intlRevenuePct(intlRevPct)
                .domRevenuePct(totRev.signum() > 0 ? 100 - intlRevPct : 0)
                .intlBookingsPct(intlBkgPct)
                .domBookingsPct(totBkg > 0 ? 100 - intlBkgPct : 0)
                .totalRevenue(totRev)
                .totalBookings(totBkg)
                .intlRevenue(nz(intl.getTotalRevenue()))
                .domRevenue(nz(dom.getTotalRevenue()))
                .intlBookings(intl.getTotalBookings())
                .domBookings(dom.getTotalBookings())
                .build();
    }

    private TripTypeDataDTO emptyPanel() {
        return TripTypeDataDTO.builder()
                .totalRevenue(BigDecimal.ZERO)
                .totalBookings(0)
                .avgValue(BigDecimal.ZERO)
                .avgNights(0.0)
                .tcs(BigDecimal.ZERO)
                .growthPct(0.0)
                .destinations(List.of())
                .build();
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read intl/domestic reports.");
        }
        return tenantId;
    }

    private static BigDecimal sum(List<Booking> list, java.util.function.Function<Booking, BigDecimal> f) {
        return list.stream().map(f).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BookingStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return BookingStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}