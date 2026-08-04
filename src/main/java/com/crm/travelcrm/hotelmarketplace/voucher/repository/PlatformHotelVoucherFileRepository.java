package com.crm.travelcrm.hotelmarketplace.voucher.repository;

import com.crm.travelcrm.hotelmarketplace.voucher.entity.PlatformHotelVoucherFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The hotel-supplied voucher PDFs.
 *
 * <p>Deliberately tiny, and deliberately the ONLY way these rows are reached. Every finder here
 * loads the {@code content} column, so nothing may call it on a list path — the whole reason the
 * bytes live in a side table is that {@code PlatformHotelBooking} must stay cheap to read.</p>
 */
public interface PlatformHotelVoucherFileRepository extends JpaRepository<PlatformHotelVoucherFile, Long> {

    /** The current file for a booking. At most one, enforced by {@code uq_phvf_booking_current}. */
    Optional<PlatformHotelVoucherFile> findByHotelBookingIdAndDeletedAtIsNull(Long hotelBookingId);
}
