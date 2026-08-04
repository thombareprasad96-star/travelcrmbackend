package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.response.BookingTimelineEventDTO;

import java.util.List;
import java.util.UUID;

/**
 * Read-only activity timeline of one booking — who did what, when. Composed on the fly from the
 * booking's Envers revisions, its payment/expense ledgers, the cancellation record and its tax
 * invoices; see {@link BookingTimelineServiceImpl} for the exact event sources.
 */
public interface BookingTimelineService {

    /** Events newest-first. The booking is resolved tenant-scoped; a foreign publicId 404s. */
    List<BookingTimelineEventDTO> getTimeline(UUID bookingPublicId);
}
