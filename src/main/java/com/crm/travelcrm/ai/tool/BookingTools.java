package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only booking tools. Delegates to {@link BookingService} (tenant-scoped). Sensitive internals
 * (vendorCost, netProfit) and internal Long ids are deliberately NOT surfaced to the model.
 */
@Component
@RequiredArgsConstructor
public class BookingTools {

    private final BookingService bookingService;
    private final AiAuditService audit;

    public record BookingSummary(String publicId, String bookingCode, String customer, String destination,
                                 String bookingDate, String travelDate, String status, String paymentStatus,
                                 String customerAmount, String totalPayable, String paidAmount,
                                 String pendingAmount) {}

    @Tool(description = "List bookings in the current tenant (paginated, newest first). "
            + "Returns booking summaries with code, customer, dates, status and payment totals.")
    public List<BookingSummary> findBookings(
            @ToolParam(required = false, description = "Zero-based page number (default 0)") Integer page,
            @ToolParam(required = false, description = "Page size, max 50 (default 20)") Integer size) {
        return audit.recordToolCall("findBookings", Map.of(
                        "page", ToolFmt.pageOrDefault(page), "size", ToolFmt.sizeOrDefault(size)),
                () -> bookingService.getAll(ToolFmt.pageOrDefault(page), ToolFmt.sizeOrDefault(size),
                                "bookingDate", "desc")
                        .getData().stream().map(BookingTools::toSummary).toList());
    }

    @Tool(description = "Get one booking by its publicId (UUID).")
    public BookingSummary getBookingDetails(
            @ToolParam(description = "The booking's publicId (UUID)") String bookingPublicId) {
        return audit.recordToolCall("getBookingDetails", Map.of("bookingPublicId", ToolFmt.str(bookingPublicId)),
                () -> toSummary(bookingService.getById(ToolFmt.uuid(bookingPublicId))));
    }

    @Tool(description = "Get one booking by its human booking code, e.g. 'BK10003'.")
    public BookingSummary getBookingByCode(
            @ToolParam(description = "The booking code, e.g. BK10003") String bookingCode) {
        return audit.recordToolCall("getBookingByCode", Map.of("bookingCode", ToolFmt.str(bookingCode)),
                () -> toSummary(bookingService.getByCode(bookingCode)));
    }

    private static BookingSummary toSummary(BookingResponseDTO b) {
        return new BookingSummary(
                ToolFmt.str(b.getPublicId()), b.getBookingCode(),
                b.getCustomerNameSnapshot(), b.getDestinationSnapshot(),
                ToolFmt.str(b.getBookingDate()), ToolFmt.str(b.getTravelDate()),
                ToolFmt.str(b.getStatus()), ToolFmt.str(b.getPaymentStatus()),
                ToolFmt.str(b.getCustomerAmount()), ToolFmt.str(b.getTotalPayable()),
                ToolFmt.str(b.getPaidAmount()), ToolFmt.str(b.getPendingAmount()));
    }
}