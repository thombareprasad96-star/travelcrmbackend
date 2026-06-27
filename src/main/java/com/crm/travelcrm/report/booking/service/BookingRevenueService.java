package com.crm.travelcrm.report.booking.service;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.report.booking.dto.BookingRevenueResponseDTO;
import com.crm.travelcrm.report.booking.dto.BookingStatisticsDTO;
import com.crm.travelcrm.report.booking.dto.RevenueBookingRowDTO;
import com.crm.travelcrm.report.booking.dto.RevenueBreakdownDTO;
import com.crm.travelcrm.report.booking.dto.RevenueSummaryDTO;
import com.crm.travelcrm.report.booking.repository.BookingReportRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Read side of the Booking Revenue report. Tenant-scoped via {@link TenantContext}; money stays in
 * {@link BigDecimal}. Bare DTOs per the reports contract. {@code tripType}/{@code refunded} have no
 * column on Booking, so trip type is null and the international split / refunded are 0.
 */
@Service
@RequiredArgsConstructor
public class BookingRevenueService {

    private static final DateTimeFormatter TRAVEL_FMT  = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final DateTimeFormatter CREATED_FMT = DateTimeFormatter.ofPattern("MMM dd, yy");

    private final BookingReportRepository repository;

    @Transactional(readOnly = true)
    public BookingRevenueResponseDTO getBookings(String startDate, String endDate, String dateType,
                                                 String status, String paymentStatus,
                                                 String minAmount, String maxAmount, String search,
                                                 int perPage, int page) {
        List<Booking> all = query(startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount, search);

        int safePage    = Math.max(page, 1);
        int safePerPage = Math.min(Math.max(perPage, 1), 500);
        int fromIdx = Math.min((safePage - 1) * safePerPage, all.size());
        int toIdx   = Math.min(fromIdx + safePerPage, all.size());

        List<RevenueBookingRowDTO> rows = all.subList(fromIdx, toIdx).stream().map(this::toRow).toList();

        return BookingRevenueResponseDTO.builder()
                .bookings(rows)
                .total(all.size())
                .page(safePage)
                .perPage(safePerPage)
                .totalPages((int) Math.ceil((double) all.size() / safePerPage))
                .build();
    }

    @Transactional(readOnly = true)
    public RevenueSummaryDTO getSummary(String startDate, String endDate, String dateType,
                                        String status, String paymentStatus,
                                        String minAmount, String maxAmount) {
        List<Booking> all = query(startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount, null);
        BigDecimal totalRevenue = sum(all, Booking::getCustomerAmount);
        BigDecimal netProfit    = sum(all, Booking::getNetProfit);
        BigDecimal payable      = sum(all, Booking::getTotalPayable);
        BigDecimal due          = sum(all, Booking::getPendingAmount);
        double avgMargin = payable.signum() > 0
                ? netProfit.divide(payable, 4, RoundingMode.HALF_UP).movePointRight(2)
                        .setScale(1, RoundingMode.HALF_UP).doubleValue()
                : 0.0;
        return RevenueSummaryDTO.builder()
                .totalRevenue(totalRevenue)
                .netProfit(netProfit)
                .avgNetMargin(avgMargin)
                .outstandingDue(due)
                .build();
    }

    @Transactional(readOnly = true)
    public RevenueBreakdownDTO getBreakdown(String startDate, String endDate, String dateType) {
        List<Booking> all = query(startDate, endDate, dateType, null, null, null, null, null);
        return RevenueBreakdownDTO.builder()
                .tcs(sum(all, Booking::getTcs))
                .totalPayable(sum(all, Booking::getTotalPayable))
                .paidAmount(sum(all, Booking::getPaidAmount))
                .refunded(BigDecimal.ZERO)
                .build();
    }

    @Transactional(readOnly = true)
    public BookingStatisticsDTO getStatistics(String startDate, String endDate, String dateType) {
        List<Booking> all = query(startDate, endDate, dateType, null, null, null, null, null);
        long confirmed = all.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long completed = all.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count();
        long cancelled = all.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).count();
        return BookingStatisticsDTO.builder()
                .international(0)
                .domestic(all.size())
                .confirmed(confirmed)
                .completed(completed)
                .cancelled(cancelled)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, String dateType, String status,
                            String paymentStatus, String minAmount, String maxAmount, String search) {
        List<Booking> all = query(startDate, endDate, dateType, status, paymentStatus, minAmount, maxAmount, search);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Booking Code", "Customer", "Customer Amount", "TCS", "Total Payable",
                    "Paid", "Due", "Vendor Cost", "Net Profit", "Net Margin %", "Status", "Travel Date");
            for (Booking b : all) {
                RevenueBookingRowDTO r = toRow(b);
                printer.printRecord(r.getCode(), r.getCustomer(), r.getCustomerAmount(), r.getTcs(),
                        r.getTotalPayable(), r.getPaid(), r.getDue(), r.getVendorCost(), r.getNetProfit(),
                        r.getNetMargin(), r.getStatus(), r.getTravelDate());
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate revenue CSV export", e);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private List<Booking> query(String startDate, String endDate, String dateType, String status,
                                String paymentStatus, String minAmount, String maxAmount, String search) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        return repository.findRevenueList(
                tenantId,
                dateType == null || dateType.isBlank() ? "Booking Date" : dateType,
                range[0].toLocalDate(), range[1].toLocalDate(), range[0], range[1],
                parseStatus(status), parsePaymentStatus(paymentStatus),
                parseAmount(minAmount), parseAmount(maxAmount), blankToNull(search));
    }

    private RevenueBookingRowDTO toRow(Booking b) {
        BigDecimal payable = nz(b.getTotalPayable());
        double netMargin = payable.signum() > 0
                ? nz(b.getNetProfit()).divide(payable, 4, RoundingMode.HALF_UP).movePointRight(2)
                        .setScale(1, RoundingMode.HALF_UP).doubleValue()
                : 0.0;
        return RevenueBookingRowDTO.builder()
                .publicId(b.getPublicId())
                .code(b.getBookingCode())
                .customer(b.getCustomerNameSnapshot())
                .customerDetail(b.getCustomerNameSnapshot() + " - " + b.getDestinationSnapshot())
                .customerPhone(null)
                .customerAmount(nz(b.getCustomerAmount()))
                .tcs(nz(b.getTcs()))
                .totalPayable(payable)
                .paid(nz(b.getPaidAmount()))
                .due(b.getPendingAmount())
                .vendorCost(nz(b.getVendorCost()))
                .netProfit(nz(b.getNetProfit()))
                .netMargin(netMargin)
                // No trip-type column on Booking; all bookings are treated as Domestic (see class note).
                .type("Domestic")
                .status(titleCase(b.getStatus() != null ? b.getStatus().name() : null))
                .travelDate(b.getTravelDate() != null ? b.getTravelDate().format(TRAVEL_FMT) : null)
                .createdDate(b.getCreatedAt() != null ? b.getCreatedAt().format(CREATED_FMT) : null)
                .build();
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read revenue reports.");
        }
        return tenantId;
    }

    private static BigDecimal sum(List<Booking> list, java.util.function.Function<Booking, BigDecimal> f) {
        return list.stream().map(f).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String titleCase(String enumName) {
        if (enumName == null || enumName.isBlank()) return null;
        return enumName.substring(0, 1).toUpperCase() + enumName.substring(1).toLowerCase();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BookingStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return BookingStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Maps the FE payment-status labels ("Fully Paid", "Partially Paid", …) to the enum. */
    private static PaymentStatus parsePaymentStatus(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().toLowerCase();
        return switch (v) {
            case "fully paid", "paid"            -> PaymentStatus.PAID;
            case "partially paid", "partial"     -> PaymentStatus.PARTIAL;
            case "unpaid"                        -> PaymentStatus.UNPAID;
            case "refunded"                      -> PaymentStatus.REFUNDED;
            default -> null;
        };
    }
}