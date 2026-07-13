package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.BookingDocument;
import com.crm.travelcrm.booking.entity.Booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface CancellationDocumentService {

    /**
     * Mint the cancellation note (credit note when the customer is refunded, debit note when they owe)
     * and persist it WITHOUT rendering — snapshot + number only. Called inside the cancel transaction
     * so the number is reserved atomically with the money; sets the number + doc id back on the record.
     */
    BookingDocument issueCancellationNote(Booking booking, BookingCancellation record, CancellationQuote quote);

    /**
     * Mint a refund voucher acknowledging ONE disbursement, persisted (snapshot + number only) inside
     * the refund transaction. {@code totalRefundedToDate} is the cumulative figure after this payout.
     * Returns the document so the caller can echo its number/publicId.
     */
    BookingDocument issueRefundVoucher(Booking booking, BookingCancellation record,
                                       BigDecimal amount, String method, String reference,
                                       LocalDate refundDate, BigDecimal totalRefundedToDate);

    /** The credit/debit note PDF for a booking — rendered and frozen on first fetch, then reused. */
    byte[] renderCancellationNote(UUID bookingPublicId);

    /** The refund voucher PDF for a booking — rendered and frozen on first fetch, then reused. */
    byte[] renderRefundVoucher(UUID bookingPublicId);
}
