package com.crm.travelcrm.portal.booking;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingDetailDto;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingSummaryDto;
import com.crm.travelcrm.portal.booking.dto.TravelerPaymentStatusDto;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only traveler view of their own bookings. Every method resolves the {@link TravelerPrincipal}
 * and scopes by its {@code customerId}, so object-level ownership is enforced on every call —
 * a foreign {@code publicId} returns 404 (never another customer's data, never a 403 that confirms
 * existence). The portal filter also sets {@code TenantContext}, so the tenant filter applies too.
 */
@Service
@RequiredArgsConstructor
public class PortalBookingService {

    private final BookingRepository bookingRepository;
    private final PortalBookingMapper mapper;

    @Transactional(readOnly = true)
    public List<TravelerBookingSummaryDto> myBookings() {
        TravelerPrincipal me = CurrentTraveler.require();
        return mapper.toSummaries(
                bookingRepository.findAllByCustomerIdAndDeletedAtIsNullOrderByBookingDateDesc(me.customerId()));
    }

    @Transactional(readOnly = true)
    public TravelerBookingDetailDto getMyBooking(UUID publicId) {
        return mapper.toDetail(requireOwned(publicId));
    }

    @Transactional(readOnly = true)
    public TravelerPaymentStatusDto getPaymentStatus(UUID publicId) {
        return mapper.toPaymentStatus(requireOwned(publicId));
    }

    /** Fetch a booking ONLY if it belongs to the current traveler's customer; else 404. */
    private Booking requireOwned(UUID publicId) {
        TravelerPrincipal me = CurrentTraveler.require();
        return bookingRepository.findByPublicIdAndCustomerIdAndDeletedAtIsNull(publicId, me.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + publicId));
    }
}
