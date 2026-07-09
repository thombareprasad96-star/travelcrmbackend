package com.crm.travelcrm.portal.itinerary;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.itinerary.dto.TravelerItineraryDto;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Builds the traveler-safe day-wise itinerary for one of the caller's own bookings. Ownership is
 * enforced first (booking must belong to the traveler's customer → else 404); the itinerary is then
 * assembled from the booking's source Quotation (if any). Read-only + transactional so the mapper can
 * walk the quotation's LAZY collections.
 */
@Service
@RequiredArgsConstructor
public class PortalItineraryService {

    private final BookingRepository bookingRepository;
    private final QuotationRepository quotationRepository;
    private final PortalItineraryMapper mapper;

    @Transactional(readOnly = true)
    public TravelerItineraryDto getItinerary(UUID bookingPublicId) {
        TravelerPrincipal me = CurrentTraveler.require();

        Booking booking = bookingRepository
                .findByPublicIdAndCustomerIdAndDeletedAtIsNull(bookingPublicId, me.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));

        UUID quotationPublicId = booking.getSourceQuotationPublicId();
        Quotation quotation = quotationPublicId == null
                ? null
                : quotationRepository
                        .findByPublicIdAndTenantIdAndDeletedAtIsNull(quotationPublicId, me.tenantId())
                        .orElse(null);

        return mapper.toItinerary(booking, quotation);
    }
}
