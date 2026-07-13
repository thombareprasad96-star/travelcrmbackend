package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.entity.BookingPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingPaymentRepository extends JpaRepository<BookingPayment, Long> {

    /** Ledger for a booking, newest receipt first. */
    List<BookingPayment> findByBookingIdAndDeletedAtIsNullOrderByPaymentDateDescIdDesc(Long bookingId);

    /** One receipt, scoped to its booking so a foreign publicId can never be reached. */
    Optional<BookingPayment> findByPublicIdAndBookingIdAndDeletedAtIsNull(UUID publicId, Long bookingId);

    /** Per-service payment history. */
    List<BookingPayment> findByServiceItemIdAndDeletedAtIsNullOrderByPaymentDateDescIdDesc(Long serviceItemId);

    /** An existing refund payout on this booking with the given idempotency token, if any. */
    Optional<BookingPayment> findByBookingIdAndIdempotencyKeyAndDeletedAtIsNull(Long bookingId, String idempotencyKey);
}