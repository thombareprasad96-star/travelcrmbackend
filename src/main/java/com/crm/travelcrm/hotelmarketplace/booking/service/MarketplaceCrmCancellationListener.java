package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.booking.api.BookingCancelledEvent;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The customer called off the trip, so the rooms ordered against it have to be unwound.
 *
 * <p><b>The inverse of {@link MarketplaceApprovalOrchestrator}.</b> Without it the failure is silent
 * and expensive: {@code platform_hotel_bookings} stays CONFIRMED, the supplier goes on holding a
 * room nobody will use, the commission accrual goes on standing — and worse,
 * {@code CrmBookingLinkPort.requireLinkable} now refuses that booking as terminal, so there is no
 * path left to restate the payable either. The platform learns about it when the hotel invoices for
 * a no-show.</p>
 *
 * <h3>Why a listener and not a call</h3>
 * The booking module publishes {@link BookingCancelledEvent} and knows nothing about who listens.
 * Delete this package and the event simply has no subscribers — no compile error, no dead call, no
 * edit to the CRM. That is the plug-and-play boundary expressed in code rather than in a comment,
 * and it is the reason the dependency runs marketplace → booking and never back.
 *
 * <h3>What it deliberately does NOT do</h3>
 * It does not cancel outright. A CONFIRMED room is a commitment to a supplier, and what it costs to
 * hand back is something an operator establishes by asking the hotel — so this raises
 * {@code CANCEL_REQUESTED} and puts the row in the SuperAdmin queue. Cancelling it here would mean
 * inventing a cancellation charge, and inventing one is how the platform ends up absorbing it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceCrmCancellationListener {

    private final PlatformHotelBookingRepository repository;
    private final MarketplacePlatformWriter platformWriter;
    private final PlatformAuditRecorder auditRecorder;

    /**
     * Published {@code afterCommit} by {@code BookingServiceImpl.cancel()}, so by the time this runs
     * the cancellation, its credit note and its frozen P&amp;L are all durable. Acting on a
     * cancellation the database later rolled back — the losing side of two concurrent cancels —
     * would release a room that is still sold.
     *
     * <p>Nothing here is allowed to throw. The CRM cancellation has already committed; a marketplace
     * row that could not be raised is an operational problem to log and chase, not a reason to make
     * the customer's cancellation look like it failed.</p>
     */
    @EventListener
    public void onBookingCancelled(BookingCancelledEvent event) {
        List<PlatformHotelBooking> attached =
                repository.findByCrmBookingPublicIdAndDeletedAtIsNull(event.bookingPublicId());
        if (attached.isEmpty()) {
            return;
        }

        for (PlatformHotelBooking row : attached) {
            try {
                platformWriter
                        .onCrmBookingCancelled(row.getPublicId(), event.tenantId(), event.reason())
                        .ifPresent(updated -> {
                            log.warn("CRM booking {} was cancelled — marketplace order {} moved to {}",
                                    event.bookingCode(), updated.getBookingCode(), updated.getStatus());
                            auditRecorder.safeRecord(
                                    PlatformAuditAction.MARKETPLACE_BOOKING_CANCEL_REQUESTED, true,
                                    updated.getTenantId(), updated.getTenantCode(),
                                    "MARKETPLACE_BOOKING", updated.getPublicId(),
                                    "CRM booking " + event.bookingCode() + " was cancelled ("
                                            + event.reason() + "). Unwind with the supplier.");
                        });
            } catch (RuntimeException e) {
                log.error("CRM booking {} cancelled but marketplace order {} could not be unwound: {}",
                        event.bookingCode(), row.getBookingCode(), e.getMessage(), e);
            }
        }
    }
}
