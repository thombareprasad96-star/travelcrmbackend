package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.dto.request.RefundBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.RefundResponseDTO;

import java.util.UUID;

/**
 * Records refund disbursements against a cancelled booking. Every rupee is settled here — the amount
 * is capped to the frozen refund due, accumulated onto the version-guarded {@code Booking.refundedAmount},
 * mirrored to the cancellation's {@code refundStatus}, and acknowledged with a numbered refund voucher —
 * all in one transaction.
 */
public interface BookingRefundService {

    RefundResponseDTO refund(UUID bookingPublicId, RefundBookingRequestDTO request);
}
