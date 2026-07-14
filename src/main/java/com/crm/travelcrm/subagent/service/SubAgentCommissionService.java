package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.subagent.entity.SubAgentCommission;
import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.repository.SubAgentCommissionRepository;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Accrues (and reverses) a sub-agent's broker commission as a booking is paid down. A sub-agent is a
 * broker: the customer pays the parent's sale price and the sub-agent earns a commission carved out of
 * it (the parent keeps the rest). Commission is earned ON PAYMENT RECEIVED — proportional to net
 * collection — so a refund or a deleted receipt naturally reverses it.
 *
 * <p>{@link #syncForBooking} is the single entry point: it recomputes the target accrued commission
 * for the booking and records only the delta versus what the ledger already holds, so it is safe to
 * call after every payment/refund without double-counting. A no-op unless the booking's owner is a
 * sub-agent with a profile.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentCommissionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SubAgentCommissionRepository commissionRepository;
    private final SubAgentProfileRepository profileRepository;

    /**
     * Reconcile the booking's accrued commission to its current net-collection level. Records a signed
     * delta row when the target differs from the ledger sum. Call within the caller's transaction after
     * {@code paidAmount}/{@code refundedAmount} change.
     */
    public void syncForBooking(Booking booking) {
        Long ownerId = booking.getOwnerUserId();
        if (ownerId == null) {
            return;   // no owner stamped (legacy/staff path)
        }
        SubAgentProfile profile = profileRepository.findByUserIdAndDeletedAtIsNull(ownerId).orElse(null);
        if (profile == null) {
            return;   // owner is not a sub-agent → no commission
        }

        BigDecimal totalCommission = totalCommission(profile, nz(booking.getCustomerAmount()));
        BigDecimal target = totalCommission.multiply(collectedFraction(booking)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal current = nz(commissionRepository.sumByBookingId(booking.getId()));
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) {
            return;
        }

        SubAgentCommission entry = SubAgentCommission.builder()
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .subAgentUserId(ownerId)
                .amount(delta)
                .note(delta.signum() > 0 ? "Accrual on payment" : "Reversal (refund / receipt removed)")
                .occurredOn(LocalDate.now())
                .build();
        commissionRepository.save(entry);
        log.info("Commission {}{} on booking {} (sub-agent {}) -> accrued {}",
                delta.signum() > 0 ? "+" : "", delta, booking.getBookingCode(), ownerId, target);
    }

    /** Full commission for a booking once completely collected: PERCENT of sale value, or a FIXED per-booking amount. */
    private BigDecimal totalCommission(SubAgentProfile profile, BigDecimal saleValue) {
        BigDecimal rate = nz(profile.getMarkupValue());
        return profile.getMarkupType() == MarkupType.PERCENT
                ? saleValue.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : rate.setScale(2, RoundingMode.HALF_UP);
    }

    /** Net-collected fraction of the booking (paid − refunded) / totalPayable, clamped to [0, 1]. */
    private BigDecimal collectedFraction(Booking booking) {
        BigDecimal payable = nz(booking.getTotalPayable());
        if (payable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = nz(booking.getPaidAmount()).subtract(nz(booking.getRefundedAmount()));
        if (net.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return net.divide(payable, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
