package com.crm.travelcrm.booking.cancellation.controller;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.dto.CancellationSummaryDTO;
import com.crm.travelcrm.booking.cancellation.service.BookingCancellationService;
import com.crm.travelcrm.booking.cancellation.service.BookingRefundService;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.dto.request.RefundBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.RefundResponseDTO;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cancellation / refund endpoints for a booking. Phase 3 exposes the dry-run preview; confirm,
 * refund and document endpoints are added in later phases under this same base path.
 */
@RestController
@RequestMapping("/api/bookings/{publicId}/cancellation")
@RequiredArgsConstructor
public class BookingCancellationController {

    private static final Logger log = LogManager.getLogger(BookingCancellationController.class);

    private final BookingCancellationService cancellationService;
    private final CancellationDocumentService documentService;
    private final BookingRefundService refundService;

    /**
     * Dry-run: what would cancelling this booking retain and refund right now? No mutation.
     * Optional {@code overrideChargeBase} / {@code vendorRecoverable} let the UI preview the exact
     * figures of a proposed waiver/override before confirming — the backend computes every rupee.
     */
    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('BOOKING_CANCEL')")
    public ResponseEntity<ApiResponse<CancellationQuote>> preview(
            @PathVariable UUID publicId,
            @RequestParam(value = "overrideChargeBase", required = false) BigDecimal overrideChargeBase,
            @RequestParam(value = "vendorRecoverable", required = false) BigDecimal vendorRecoverable) {
        log.info("GET /api/bookings/{}/cancellation/preview (override={})", publicId, overrideChargeBase);
        return ResponseEntity.ok(ApiResponse.success("Cancellation preview computed",
                cancellationService.preview(publicId, overrideChargeBase, vendorRecoverable)));
    }

    /** The frozen refund position of a cancelled booking (due / paid-out / remaining). */
    @GetMapping
    @PreAuthorize("hasAuthority('BOOKING_READ')")
    public ResponseEntity<ApiResponse<CancellationSummaryDTO>> summary(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Cancellation summary",
                cancellationService.getSummary(publicId)));
    }

    /** The credit note (or debit note when the customer owes) PDF issued for this cancellation. */
    @GetMapping("/credit-note")
    @PreAuthorize("hasAuthority('BOOKING_READ')")
    public ResponseEntity<byte[]> creditNote(@PathVariable UUID publicId) {
        byte[] pdf = documentService.renderCancellationNote(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=cancellation-note-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** The refund voucher PDF (available once a refund has been disbursed). */
    @GetMapping("/refund-voucher")
    @PreAuthorize("hasAuthority('BOOKING_READ')")
    public ResponseEntity<byte[]> refundVoucher(@PathVariable UUID publicId) {
        byte[] pdf = documentService.renderRefundVoucher(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=refund-voucher-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Record a refund disbursement against a cancelled booking; issues a refund voucher. */
    @PostMapping("/refund")
    @PreAuthorize("hasAuthority('BOOKING_REFUND')")
    public ResponseEntity<ApiResponse<RefundResponseDTO>> refund(
            @PathVariable UUID publicId,
            @Valid @RequestBody RefundBookingRequestDTO request) {
        log.info("POST /api/bookings/{}/cancellation/refund - amount {}", publicId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success("Refund recorded",
                refundService.refund(publicId, request)));
    }
}
