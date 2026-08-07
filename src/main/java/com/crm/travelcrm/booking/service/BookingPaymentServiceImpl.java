package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CreateBookingPaymentRequest;
import com.crm.travelcrm.booking.dto.response.BookingPaymentResponse;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.subagent.service.SubAgentCommissionService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payment-ledger implementation. The booking's {@code paidAmount} is treated as the running
 * balance: a receipt increases it, deleting a receipt decreases it (floored at zero). This is
 * additive on purpose — it preserves any historical paid amount that predates the ledger rather
 * than recomputing purely from rows.
 */
@Service
@RequiredArgsConstructor
public class BookingPaymentServiceImpl implements BookingPaymentService {

    private static final Logger log = LogManager.getLogger(BookingPaymentServiceImpl.class);

    private final BookingRepository            bookingRepository;
    private final BookingPaymentRepository     paymentRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final SubAgentCommissionService    commissionService;
    private final SubAgentScope                subAgentScope;

    @Override
    @Transactional(readOnly = true)
    public List<BookingPaymentResponse> getPayments(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        return paymentRepository
                .findByBookingIdAndDeletedAtIsNullOrderByPaymentDateDescIdDesc(booking.getId())
                .stream()
                .map(p -> toResponse(p, resolveServiceItemPublicId(p)))
                .toList();
    }

    @Override
    @Transactional
    public BookingPaymentResponse addPayment(UUID bookingPublicId, CreateBookingPaymentRequest request) {
        Booking booking = findActiveBooking(bookingPublicId);
        assertLedgerMutable(booking);

        BigDecimal newPaid = booking.getPaidAmount().add(request.getAmount());
        if (newPaid.compareTo(booking.getTotalPayable()) > 0) {
            throw new BusinessException(
                    "Total paid ₹" + newPaid + " would exceed the total payable ₹"
                            + booking.getTotalPayable() + ".",
                    HttpStatus.CONFLICT);
        }

        // Optional attribution to a service line — must belong to this same booking.
        BookingServiceItem serviceItem = null;
        if (request.getServiceItemPublicId() != null) {
            serviceItem = serviceItemRepository
                    .findByPublicIdAndBookingIdAndDeletedAtIsNull(
                            request.getServiceItemPublicId(), booking.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Service line not found on this booking: " + request.getServiceItemPublicId()));
        }

        BookingPayment payment = BookingPayment.builder()
                .bookingId(booking.getId())
                .serviceItemId(serviceItem != null ? serviceItem.getId() : null)
                .amount(request.getAmount())
                .paymentType(request.getPaymentType())
                .paymentMethod(request.getPaymentMethod())
                .paymentDate(request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now())
                .reference(request.getReference())
                .notes(request.getNotes())
                .build();
        BookingPayment saved = paymentRepository.save(payment);

        booking.setPaidAmount(newPaid);
        booking.setPaymentStatus(
                derivePaymentStatus(newPaid, booking.getTotalPayable(), booking.getPaymentStatus()));
        bookingRepository.save(booking);

        // Broker commission accrues as the customer pays (no-op unless the booking is sub-agent-owned).
        commissionService.syncForBooking(booking);

        log.info("Payment {} (₹{}) recorded on booking {} -> paid {}/{} [{}]",
                saved.getPublicId(), saved.getAmount(), booking.getBookingCode(),
                newPaid, booking.getTotalPayable(), booking.getPaymentStatus());

        return toResponse(saved, serviceItem != null ? serviceItem.getPublicId() : null);
    }

    @Override
    @Transactional
    public void deletePayment(UUID bookingPublicId, UUID paymentPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        assertLedgerMutable(booking);

        BookingPayment payment = paymentRepository
                .findByPublicIdAndBookingIdAndDeletedAtIsNull(paymentPublicId, booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found on this booking: " + paymentPublicId));

        // A refund disbursement is a financial record with its own voucher; it is never a receipt and
        // must not be removed here (that would also desync Booking.refundedAmount). Refunds are managed
        // through the refund flow only.
        if (payment.isRefund()) {
            throw new BusinessException(
                    "Refund entries cannot be deleted from the payment ledger.", HttpStatus.CONFLICT);
        }

        BigDecimal newPaid = booking.getPaidAmount().subtract(payment.getAmount());
        if (newPaid.compareTo(BigDecimal.ZERO) < 0) {
            newPaid = BigDecimal.ZERO;
        }

        payment.softDelete(currentUserEmail());
        paymentRepository.save(payment);

        booking.setPaidAmount(newPaid);
        booking.setPaymentStatus(
                derivePaymentStatus(newPaid, booking.getTotalPayable(), booking.getPaymentStatus()));
        bookingRepository.save(booking);

        // Removing a receipt lowers net collection → reverses the proportional commission.
        commissionService.syncForBooking(booking);

        log.info("Payment {} removed from booking {} -> paid {}/{} [{}]",
                paymentPublicId, booking.getBookingCode(),
                newPaid, booking.getTotalPayable(), booking.getPaymentStatus());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Booking findActiveBooking(UUID bookingPublicId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
        subAgentScope.assertVisible(booking, bookingPublicId);
        return booking;
    }

    /**
     * A cancelled booking's receipts are frozen — the same rule {@code assertEditableBooking} applies
     * to the booking's own amounts, which this path was missing.
     *
     * <p><b>Why this is a money guard, not a tidiness one.</b> Cancelling freezes a settlement onto
     * the {@code BookingCancellation} record: {@code refundDue = paidAmount − totalRetained},
     * computed once from the {@code paidAmount} standing at that instant, then published as a
     * numbered credit or debit note. {@code BookingRefundService} pays out against that frozen
     * {@code refundDue} and nothing re-derives it. So deleting a ₹1,00,000 receipt after a
     * cancellation that retained ₹20,000 left {@code paidAmount} at 0 while {@code refundDue} still
     * read ₹80,000 — and the agency could then disburse ₹80,000 it had never received. Adding a
     * receipt failed the other way, understating what the customer was owed. Either way the issued
     * note stops describing the money.
     *
     * <p>Correcting a mis-keyed receipt after cancellation is therefore not an edit: it is a new
     * transaction, and the honest answer is to refuse here rather than retroactively rewrite what a
     * document already states.
     */
    private static void assertLedgerMutable(Booking booking) {
        if (booking.getStatus() == BookingStatus.CANCELLED
                || booking.getStatus() == BookingStatus.REFUNDED) {
            throw new BusinessException(
                    "A " + booking.getStatus() + " booking's payment ledger is closed — its refund "
                            + "settlement is already frozen on the cancellation note.",
                    HttpStatus.CONFLICT);
        }
    }

    /** Resolve the attributed service line's publicId (for the response), or null. */
    private UUID resolveServiceItemPublicId(BookingPayment payment) {
        if (payment.getServiceItemId() == null) return null;
        return serviceItemRepository.findById(payment.getServiceItemId())
                .map(BookingServiceItem::getPublicId)
                .orElse(null);
    }

    private static PaymentStatus derivePaymentStatus(
            BigDecimal paidAmount, BigDecimal totalPayable, PaymentStatus current) {
        // REFUNDED is a terminal payment state — a later ledger add/delete must never silently
        // downgrade it back to PAID/PARTIAL/UNPAID (which would also re-enter it into revenue stats).
        if (current == PaymentStatus.REFUNDED)          return PaymentStatus.REFUNDED;
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) return PaymentStatus.UNPAID;
        if (paidAmount.compareTo(totalPayable) >= 0)    return PaymentStatus.PAID;
        return PaymentStatus.PARTIAL;
    }

    private String currentUserEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private BookingPaymentResponse toResponse(BookingPayment p, UUID serviceItemPublicId) {
        return BookingPaymentResponse.builder()
                .publicId(p.getPublicId())
                .amount(p.getAmount())
                .entryType(p.isRefund() ? "REFUND" : "RECEIPT")
                .paymentType(p.getPaymentType())
                .paymentMethod(p.getPaymentMethod())
                .paymentDate(p.getPaymentDate())
                .reference(p.getReference())
                .notes(p.getNotes())
                .serviceItemPublicId(serviceItemPublicId)
                .createdBy(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
