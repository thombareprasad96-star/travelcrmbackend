package com.crm.travelcrm.portal.booking;

import com.crm.travelcrm.booking.cancellation.enums.DocumentType;
import com.crm.travelcrm.booking.cancellation.repository.BookingDocumentRepository;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.service.BookingDocumentService;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingDocumentDto;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Traveler-facing booking documents (Invoice, cancellation Credit/Debit Note, Refund Voucher).
 *
 * <p>Every method FIRST re-resolves the booking by the current traveler's {@code customerId}
 * ({@link #requireOwned}) — a foreign {@code publicId} yields 404, never another customer's document.
 * Only after ownership is proven does it delegate to the same server-side renderers the staff app
 * uses, so the traveler sees byte-identical PDFs. Those renderers already emit customer-safe output
 * (no vendor cost / margin), and their own by-publicId lookup can never widen the scope this method
 * already narrowed.
 */
@Service
@RequiredArgsConstructor
public class PortalBookingDocumentService {

    private static final List<DocumentType> NOTE_TYPES =
            List.of(DocumentType.CREDIT_NOTE, DocumentType.DEBIT_NOTE);

    private final BookingRepository bookingRepository;
    private final BookingDocumentService bookingDocumentService;          // invoice
    private final CancellationDocumentService cancellationDocumentService; // note + refund voucher
    private final BookingDocumentRepository cancellationDocumentRepository;

    /** Which documents this traveler can download for the booking — only ones that actually exist. */
    @Transactional(readOnly = true)
    public List<TravelerBookingDocumentDto> listDocuments(UUID bookingPublicId) {
        Booking booking = requireOwned(bookingPublicId);
        Long id = booking.getId();

        List<TravelerBookingDocumentDto> docs = new ArrayList<>();
        // The invoice is always renderable on the fly (it reflects current paid/pending figures,
        // and is voided to zero balance once the booking is cancelled).
        docs.add(TravelerBookingDocumentDto.builder()
                .type("INVOICE").label("Tax Invoice").path("invoice").build());

        cancellationDocumentRepository
                .findFirstByBookingIdAndDocTypeInAndDeletedAtIsNullOrderByIdDesc(id, NOTE_TYPES)
                .ifPresent(note -> docs.add(TravelerBookingDocumentDto.builder()
                        .type(note.getDocType().name())
                        .label(note.getDocType().title())
                        .path("credit-note").build()));

        cancellationDocumentRepository
                .findFirstByBookingIdAndDocTypeAndDeletedAtIsNullOrderByIdDesc(id, DocumentType.REFUND_VOUCHER)
                .ifPresent(rv -> docs.add(TravelerBookingDocumentDto.builder()
                        .type(rv.getDocType().name())
                        .label(rv.getDocType().title())
                        .path("refund-voucher").build()));

        return docs;
    }

    public byte[] invoice(UUID bookingPublicId) {
        requireOwned(bookingPublicId);
        return bookingDocumentService.renderInvoice(bookingPublicId);
    }

    public byte[] creditNote(UUID bookingPublicId) {
        requireOwned(bookingPublicId);
        return cancellationDocumentService.renderCancellationNote(bookingPublicId);
    }

    public byte[] refundVoucher(UUID bookingPublicId) {
        requireOwned(bookingPublicId);
        return cancellationDocumentService.renderRefundVoucher(bookingPublicId);
    }

    /** Fetch a booking ONLY if it belongs to the current traveler's customer; else 404. */
    private Booking requireOwned(UUID publicId) {
        TravelerPrincipal me = CurrentTraveler.require();
        return bookingRepository.findByPublicIdAndCustomerIdAndDeletedAtIsNull(publicId, me.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + publicId));
    }
}
