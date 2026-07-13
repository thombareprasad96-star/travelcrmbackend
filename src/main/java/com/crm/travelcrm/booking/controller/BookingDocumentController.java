package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.service.BookingDocumentService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Booking documents — Invoice and Voucher — rendered as PDF on the fly (no caching), so an
 * invoice always reflects the current paid/pending figures. Both require {@code BOOKING_READ}.
 */
@RestController
@RequestMapping("/api/bookings/{publicId}")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")
public class BookingDocumentController {

    private static final Logger log = LogManager.getLogger(BookingDocumentController.class);

    private final BookingDocumentService documentService;

    @GetMapping("/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable UUID publicId) {
        log.info("GET /api/bookings/{}/invoice", publicId);
        byte[] pdf = documentService.renderInvoice(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=invoice-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping("/voucher")
    public ResponseEntity<byte[]> voucher(@PathVariable UUID publicId) {
        log.info("GET /api/bookings/{}/voucher", publicId);
        byte[] pdf = documentService.renderVoucher(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=voucher-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}