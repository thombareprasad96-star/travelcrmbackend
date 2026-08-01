package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.dto.CancellationSummaryDTO;
import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingCancellationServiceImpl implements BookingCancellationService {

    private final BookingRepository bookingRepository;
    private final BookingCancellationRepository cancellationRepository;
    private final CancellationPolicyResolver policyResolver;
    private final CancellationCalculator calculator;
    private final SubAgentScope subAgentScope;

    @Override
    @Transactional(readOnly = true)   // readOnly: a stray write fails fast instead of committing
    public CancellationQuote preview(UUID bookingPublicId) {
        return preview(bookingPublicId, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public CancellationQuote preview(UUID bookingPublicId, BigDecimal overrideChargeBase,
                                     BigDecimal vendorRecoverable) {
        Booking booking = findVisibleBooking(bookingPublicId);
        CancellationPolicy policy = resolveGoverningPolicy(booking);
        // With no override the charge is policy-computed and vendor cost is assumed fully sunk
        // (worst-case P&L); a supplied override/recovery lets the UI preview the exact settled figures.
        return calculator.calculate(booking, policy, LocalDate.now(), overrideChargeBase, vendorRecoverable);
    }

    @Override
    @Transactional(readOnly = true)
    public CancellationSummaryDTO getSummary(UUID bookingPublicId) {
        Booking booking = findVisibleBooking(bookingPublicId);
        BookingCancellation record = cancellationRepository
                .findByBookingIdAndDeletedAtIsNull(booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "This booking has no cancellation record: " + bookingPublicId));

        BigDecimal refundDue = nz(record.getRefundDue());
        BigDecimal refunded = nz(booking.getRefundedAmount());
        BigDecimal remaining = refundDue.subtract(refunded).max(BigDecimal.ZERO);
        boolean fully = !record.isCustomerOwes() && refundDue.signum() > 0
                && refunded.compareTo(refundDue) >= 0;

        return CancellationSummaryDTO.builder()
                .bookingPublicId(booking.getPublicId())
                .bookingCode(booking.getBookingCode())
                .cancelledOn(record.getCancelDate())
                .reason(record.getReason())
                .customerOwes(record.isCustomerOwes())
                .customerBalanceOwed(nz(record.getCustomerBalanceOwed()))
                .refundDue(refundDue)
                .totalRefunded(refunded)
                .remainingRefundable(record.isCustomerOwes() ? BigDecimal.ZERO : remaining)
                .refundStatus(record.getRefundStatus() != null ? record.getRefundStatus().name() : null)
                .fullyRefunded(fully)
                .creditNoteNumber(record.getCreditNoteNumber())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Load a booking for a read on its cancellation, applying the same row-level scope as every other
     * by-id booking path (BookingServiceImpl.findActiveByPublicId): a sub-agent that does not own the
     * booking gets a 404, never another sub-agent's cancellation quote or settlement figures.
     */
    private Booking findVisibleBooking(UUID bookingPublicId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
        subAgentScope.assertVisible(booking, bookingPublicId);
        return booking;
    }

    /**
     * The policy that governs this booking's cancellation: the exact version it was pinned to at
     * creation, else (legacy / unpinned) the company default in force as of its booking date. May be
     * null → the calculator yields a NO_POLICY quote. Shared by preview and (later) confirm.
     */
    CancellationPolicy resolveGoverningPolicy(Booking booking) {
        Long tenantId = requireTenantId();
        CancellationPolicy policy = null;
        if (booking.getCancellationPolicyPublicId() != null) {
            policy = policyResolver.loadPinned(tenantId, booking.getCancellationPolicyPublicId()).orElse(null);
        }
        if (policy == null) {
            policy = policyResolver.companyDefaultAsOf(tenantId, booking.getBookingDate()).orElse(null);
        }
        return policy;
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }
}
