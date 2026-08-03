package com.crm.travelcrm.booking.api;

import java.util.UUID;

/**
 * A CRM booking was cancelled. Published by the booking module; consumed by whoever cares.
 *
 * <h3>Why an event and not a call</h3>
 * The hotel marketplace has to react to this — a cancelled trip must release the room held with the
 * supplier — but making {@code BookingServiceImpl.cancel()} invoke the marketplace would invert the
 * dependency the architecture depends on. Today {@code hotelmarketplace} imports {@code booking.api}
 * and <b>nothing under {@code booking} imports {@code hotelmarketplace}</b>, which is what makes the
 * marketplace a removable add-on rather than a branch of the CRM.
 *
 * <p>So booking states a fact about itself and knows nothing about who listens. Delete the
 * marketplace package and this event simply has no subscribers — no compile error, no dead call, no
 * edit to the booking module. That is the plug-and-play boundary, expressed in code rather than in
 * a comment.
 *
 * <h3>Published after commit</h3>
 * Cancellation mints an immutable cancellation record, a numbered credit note and a frozen P&amp;L.
 * A listener that acted on a cancellation the database later rolled back — the losing side of two
 * concurrent cancels — would release a room that is still sold. Publication is therefore deferred to
 * {@code afterCommit}.
 *
 * @param bookingPublicId the cancelled booking; the only identifier that crosses the boundary
 * @param bookingId       internal id, for consumers that need to query link tables by FK
 * @param tenantId        the owning tenant — a consumer running off the request thread has no
 *                        ambient {@code TenantContext} and must set its own
 * @param bookingCode     human-readable reference, for messages and audit trails
 * @param reason          why it was cancelled, as recorded on the cancellation
 */
public record BookingCancelledEvent(
        UUID bookingPublicId,
        Long bookingId,
        Long tenantId,
        String bookingCode,
        String reason) {
}
