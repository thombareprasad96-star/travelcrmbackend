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

    /**
     * The marketplace hotel line, INCLUDING a soft-deleted one.
     *
     * <p>Looking through the soft-delete is deliberate and load-bearing. The partial unique index on
     * {@code marketplace_booking_public_id} excludes deleted rows, but a soft-deleted row still holds
     * the value — so a {@code …AndDeletedAtIsNull} finder would miss it, the upsert would take its
     * INSERT branch, and the index would reject the write. Replays must find the old row and
     * un-delete it. Same reason {@code BookingExpenseRepository.findByPublicIdAndBookingId} exists.</p>
     */
    Optional<BookingServiceItem> findByMarketplaceBookingPublicId(UUID marketplaceBookingPublicId);
}