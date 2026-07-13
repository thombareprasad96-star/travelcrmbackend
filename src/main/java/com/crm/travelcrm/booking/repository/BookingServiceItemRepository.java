package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.entity.BookingServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingServiceItemRepository extends JpaRepository<BookingServiceItem, Long> {

    /** All service lines for a booking, in creation order. */
    List<BookingServiceItem> findByBookingIdAndDeletedAtIsNullOrderByIdAsc(Long bookingId);

    /** One line, scoped to its booking so a foreign publicId can never be reached. */
    Optional<BookingServiceItem> findByPublicIdAndBookingIdAndDeletedAtIsNull(UUID publicId, Long bookingId);
}