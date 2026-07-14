package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.BookingDocument;
import com.crm.travelcrm.booking.cancellation.enums.RefundStatus;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.dto.request.RefundBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.RefundResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentEntryType;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.subagent.service.SubAgentCommissionService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Refund payout implementation. See {@link BookingRefundService}. The booking is loaded fresh, so the
 * {@code @Version} bump on {@code refundedAmount} serialises concurrent payouts (the loser 409s rather
 * than double-paying); a client {@code idempotencyKey} additionally collapses an honest resubmit onto
 * the original row (UNIQUE index {@code uq_bkpay_idem} backstops the race). The refund amount can never
 * exceed the frozen {@code refundDue} minus what was already disbursed.
 */
@Service
@RequiredArgsConstructor
public class BookingRefundServiceImpl implements BookingRefundService {

    private static final Logger log = LogManager.getLogger(BookingRefundServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final BookingPaymentRepository paymentRepository;
    private final BookingCancellationRepository cancellationRepository;
    private final CancellationDocumentService documentService;
    private final SubAgentCommissionService commissionService;

    @Override
    @Transactional
    public RefundResponseDTO refund(UUID bookingPublicId, RefundBookingRequestDTO request) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));

        BookingCancellation record = cancellationRepository.findByBookingIdAndDeletedAtIsNull(booking.getId())
                .orElseThrow(() -> new BusinessException(
                        "Cancel the booking before recording a refund.", HttpStatus.BAD_REQUEST));

        if (record.isCustomerOwes()) {
            throw new BusinessException(
                    "This cancellation left a balance owed by the customer — there is nothing to refund.",
                    HttpStatus.BAD_REQUEST);
        }
        BigDecimal refundDue = nz(record.getRefundDue());
        if (refundDue.signum() <= 0) {
            throw new BusinessException("No refund is due on this booking.", HttpStatus.BAD_REQUEST);
        }

        BigDecimal already = nz(booking.getRefundedAmount());
        BigDecimal remaining = refundDue.subtract(already);
        if (remaining.signum() <= 0) {
            throw new BusinessException("This booking has already been fully refunded.", HttpStatus.CONFLICT);
        }

        // Idempotent resubmit: same key → return the original payout, disburse nothing new.
        if (StringUtils.hasText(request.getIdempotencyKey())) {
            var prior = paymentRepository.findByBookingIdAndIdempotencyKeyAndDeletedAtIsNull(
                    booking.getId(), request.getIdempotencyKey());
            if (prior.isPresent()) {
                log.info("Refund idempotency hit for booking {} key {} — returning original payout",
                        booking.getBookingCode(), request.getIdempotencyKey());
                return echoExisting(booking, record, prior.get());
            }
        }

        BigDecimal amount = nz(request.getAmount()).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new BusinessException("Refund amount must be greater than zero.", HttpStatus.BAD_REQUEST);
        }
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException(
                    "Refund of Rs." + amount + " exceeds the Rs." + remaining + " still refundable.",
                    HttpStatus.CONFLICT);
        }

        LocalDate refundDate = request.getRefundDate() != null ? request.getRefundDate() : LocalDate.now();

        // 1) Ledger row (money OUT — never touches paidAmount).
        BookingPayment payout = BookingPayment.builder()
                .bookingId(booking.getId())
                .amount(amount)
                .entryType(PaymentEntryType.REFUND)
                .paymentType("Refund")
                .paymentMethod(request.getMethod())
                .paymentDate(refundDate)
                .reference(request.getReference())
                .notes(request.getNotes())
                .idempotencyKey(StringUtils.hasText(request.getIdempotencyKey())
                        ? request.getIdempotencyKey() : null)
                .build();
        payout = paymentRepository.save(payout);

        // 2) Accumulate the authoritative refunded counter (version-guarded) + mirror the label.
        BigDecimal totalRefunded = already.add(amount);
        boolean fullyRefunded = totalRefunded.compareTo(refundDue) >= 0;
        booking.setRefundedAmount(totalRefunded);
        record.setRefundStatus(fullyRefunded ? RefundStatus.PAID : RefundStatus.PARTIALLY_PAID);
        if (fullyRefunded) {
            // Terminal: the cancelled booking is now fully settled with the customer.
            booking.setStatus(BookingStatus.REFUNDED);
            booking.setPaymentStatus(PaymentStatus.REFUNDED);
        }
        bookingRepository.save(booking);
        cancellationRepository.save(record);

        // A refund lowers net collection → reverse the sub-agent's commission proportionally (no-op
        // for staff-owned bookings).
        commissionService.syncForBooking(booking);

        // 3) Acknowledge THIS disbursement with a numbered voucher.
        BookingDocument voucher = documentService.issueRefundVoucher(
                booking, record, amount, request.getMethod(), request.getReference(),
                refundDate, totalRefunded);

        log.info("Refund Rs.{} on booking {} -> refunded {}/{} [{}]",
                amount, booking.getBookingCode(), totalRefunded, refundDue, record.getRefundStatus());

        return RefundResponseDTO.builder()
                .bookingPublicId(booking.getPublicId())
                .paymentPublicId(payout.getPublicId())
                .amount(amount)
                .method(request.getMethod())
                .reference(request.getReference())
                .refundDate(refundDate)
                .refundDue(refundDue)
                .totalRefunded(totalRefunded)
                .remainingRefundable(refundDue.subtract(totalRefunded))
                .refundStatus(record.getRefundStatus().name())
                .fullyRefunded(fullyRefunded)
                .refundVoucherNumber(voucher.getDocNumber())
                .refundVoucherDocPublicId(voucher.getPublicId())
                .build();
    }

    /** Rebuild the response for an idempotent hit without disbursing again. */
    private RefundResponseDTO echoExisting(Booking booking, BookingCancellation record, BookingPayment prior) {
        BigDecimal refundDue = nz(record.getRefundDue());
        BigDecimal totalRefunded = nz(booking.getRefundedAmount());
        return RefundResponseDTO.builder()
                .bookingPublicId(booking.getPublicId())
                .paymentPublicId(prior.getPublicId())
                .amount(prior.getAmount())
                .method(prior.getPaymentMethod())
                .reference(prior.getReference())
                .refundDate(prior.getPaymentDate())
                .refundDue(refundDue)
                .totalRefunded(totalRefunded)
                .remainingRefundable(refundDue.subtract(totalRefunded))
                .refundStatus(record.getRefundStatus().name())
                .fullyRefunded(totalRefunded.compareTo(refundDue) >= 0)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
