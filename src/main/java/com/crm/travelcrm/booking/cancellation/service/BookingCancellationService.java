package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.dto.CancellationSummaryDTO;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cancellation orchestration for a booking. Phase 3 exposes the side-effect-free preview; the
 * confirm/refund/document operations are layered on in later phases.
 */
public interface BookingCancellationService {

    /**
     * Dry-run: compute what cancelling this booking right now would retain and refund, under the
     * policy the booking is pinned to. Never mutates anything.
     */
    CancellationQuote preview(UUID bookingPublicId);

    /**
     * Same dry-run, but showing the effect of a proposed override/waiver of the retained charge
     * (and, optionally, an expected vendor recovery). The backend remains the source of truth for
     * every figure — the UI submits the proposed numbers and renders exactly what this returns,
     * so the confirmed cancellation always matches the previewed figures. Both args are optional
     * (null = policy-computed charge / vendor cost fully sunk).
     */
    CancellationQuote preview(UUID bookingPublicId, BigDecimal overrideChargeBase, BigDecimal vendorRecoverable);

    /**
     * The frozen refund position of an already-cancelled booking (due / paid-out / remaining), read
     * from its immutable cancellation record. Throws if the booking has no cancellation record.
     */
    CancellationSummaryDTO getSummary(UUID bookingPublicId);
}
