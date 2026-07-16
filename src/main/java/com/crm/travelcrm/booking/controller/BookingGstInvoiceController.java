package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.accounting.invoice.dto.CancelInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.booking.dto.request.BookingIssueInvoiceRequest;
import com.crm.travelcrm.booking.service.BookingGstInvoiceService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The GST invoice for a booking — the SAME accounting invoice the accountant issues (delegated via
 * {@link BookingGstInvoiceService}). Listing + PDF need only {@code BOOKING_READ} so any booking
 * viewer can pull the document; generating + cancelling need {@code ACCOUNTING_INVOICE_MANAGE}, i.e.
 * tenant-admin + accountant only. Method-level {@code @PreAuthorize} overrides the class default.
 */
@RestController
@RequestMapping("/api/bookings/{bookingPublicId}/gst-invoices")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")
public class BookingGstInvoiceController {

    private static final Logger log = LogManager.getLogger(BookingGstInvoiceController.class);

    private final BookingGstInvoiceService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TaxInvoiceResponse>>> list(
            @PathVariable UUID bookingPublicId,
            @AuthenticationPrincipal User currentUser) {
        log.info("GET /api/bookings/{}/gst-invoices", bookingPublicId);
        return ResponseEntity.ok(ApiResponse.success("Booking GST invoices retrieved",
                service.list(bookingPublicId, currentUser.getTenantId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_MANAGE')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> issue(
            @PathVariable UUID bookingPublicId,
            @Valid @RequestBody BookingIssueInvoiceRequest request,
            @AuthenticationPrincipal User currentUser) {
        log.info("POST /api/bookings/{}/gst-invoices", bookingPublicId);
        TaxInvoiceResponse issued = service.issue(
                bookingPublicId, request, currentUser.getTenantId(), currentUser.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("GST invoice issued: " + issued.getInvoiceNumber(), issued, 201));
    }

    @GetMapping("/{invoicePublicId}/pdf")
    public ResponseEntity<byte[]> pdf(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID invoicePublicId,
            @AuthenticationPrincipal User currentUser) {
        TaxInvoiceResponse invoice = service.get(bookingPublicId, invoicePublicId, currentUser.getTenantId());
        byte[] bytes = service.renderPdf(bookingPublicId, invoicePublicId, currentUser.getTenantId());
        String filename = "GST-Invoice-" + invoice.getInvoiceNumber().replace('/', '_') + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @PostMapping("/{invoicePublicId}/cancel")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_MANAGE')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> cancel(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID invoicePublicId,
            @Valid @RequestBody CancelInvoiceRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success("GST invoice cancelled",
                service.cancel(bookingPublicId, invoicePublicId, request.getReason(),
                        currentUser.getTenantId(), currentUser.getEmail())));
    }
}