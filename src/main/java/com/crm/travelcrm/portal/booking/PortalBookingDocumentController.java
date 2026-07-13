package com.crm.travelcrm.portal.booking;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingDocumentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Traveler-facing booking documents. Authentication is enforced by the portal chain; object-level
 * ownership is enforced in {@link PortalBookingDocumentService} (a foreign booking → 404). All PDFs
 * are streamed inline and are customer-safe (no vendor cost / agency margin).
 */
@RestController
@RequestMapping("/api/portal/bookings/{publicId}")
@RequiredArgsConstructor
public class PortalBookingDocumentController {

    private final PortalBookingDocumentService documentService;

    /** Which documents are available for this booking (invoice always; notes/voucher once issued). */
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<List<TravelerBookingDocumentDto>>> documents(@PathVariable UUID publicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Documents fetched", documentService.listDocuments(publicId)));
    }

    @GetMapping("/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable UUID publicId) {
        return pdf(documentService.invoice(publicId), "invoice-" + publicId);
    }

    @GetMapping("/credit-note")
    public ResponseEntity<byte[]> creditNote(@PathVariable UUID publicId) {
        return pdf(documentService.creditNote(publicId), "cancellation-note-" + publicId);
    }

    @GetMapping("/refund-voucher")
    public ResponseEntity<byte[]> refundVoucher(@PathVariable UUID publicId) {
        return pdf(documentService.refundVoucher(publicId), "refund-voucher-" + publicId);
    }

    private ResponseEntity<byte[]> pdf(byte[] bytes, String fileName) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=" + fileName + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }
}