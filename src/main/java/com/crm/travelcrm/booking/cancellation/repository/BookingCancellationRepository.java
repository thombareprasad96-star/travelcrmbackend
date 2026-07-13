package com.crm.travelcrm.booking.cancellation.repository;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookingCancellationRepository extends JpaRepository<BookingCancellation, Long> {

    /** The (single) cancellation record for a booking. */
    Optional<BookingCancellation> findByBookingIdAndDeletedAtIsNull(Long bookingId);

    boolean existsByBookingIdAndDeletedAtIsNull(Long bookingId);
}