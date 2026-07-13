package com.crm.travelcrm.portal.payment;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BadRequestException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.payment.dto.PaymentIntentResponse;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Portal pay: validates the booking belongs to the caller's own customer and that the amount is
 * within the pending balance, then hands off to the {@link PortalPaymentInitiation} hook. The portal
 * stays independent of whether a real Payments module exists.
 */
@Service
@RequiredArgsConstructor
public class PortalPaymentService {

    private final BookingRepository bookingRepository;
    private final PortalPaymentInitiation paymentInitiation;

    @Transactional(readOnly = true)
    public PaymentIntentResponse initiate(UUID bookingPublicId, BigDecimal requestedAmount) {
        TravelerPrincipal me = CurrentTraveler.require();
        Booking booking = bookingRepository
                .findByPublicIdAndCustomerIdAndDeletedAtIsNull(bookingPublicId, me.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));

        // A cancelled/refunded booking must not be payable — its stored pendingAmount (totalPayable −
        // paidAmount) is stale after cancellation and would otherwise let a traveler pay a dead booking.
        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.REFUNDED) {
            throw new BadRequestException("This booking has been cancelled and can no longer be paid.");
        }

        BigDecimal pending = booking.getPendingAmount();
        if (pending == null || pending.signum() <= 0) {
            throw new BadRequestException("This booking has no pending balance to pay.");
        }
        BigDecimal amount = requestedAmount != null ? requestedAmount : pending;
        if (amount.signum() <= 0) {
            throw new BadRequestException("Amount must be positive.");
        }
        if (amount.compareTo(pending) > 0) {
            throw new BadRequestException("Amount exceeds the pending balance of " + pending + ".");
        }

        return paymentInitiation.initiate(new PaymentInitiationContext(
                me.tenantId(), me.customerId(), booking.getPublicId(),
                booking.getBookingCode(), amount, pending));
    }
}
