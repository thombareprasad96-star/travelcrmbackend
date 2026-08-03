package com.crm.travelcrm.booking.api;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.customer.dto.request.CustomerResolveRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY way the hotel marketplace reaches into the CRM booking aggregate.
 *
 * <p>Dependency direction is marketplace → booking, never the reverse. Nothing but records and
 * {@code publicId}s crosses this boundary — no {@code Booking}, no {@code BookingExpense}, no
 * repository. That is what keeps the marketplace removable and stops catalog code from acquiring
 * opinions about how a booking's totals are computed.</p>
 *
 * <p>Mirrors the existing narrow-port package {@code com.crm.travelcrm.auth.api}.</p>
 *
 * <h3>The timing rule</h3>
 * {@link #requireLinkable} and {@link #createForMarketplace} run on the TENANT's own request thread,
 * where {@code TenantContext} is ambient. They must never be called from a SuperAdmin approval
 * thread: {@code OwnershipEntityListener} only stamps {@code owner_user_id} for a tenant {@code User}
 * principal, so a booking created there would be owner-less — and {@code SubAgentScope.assertVisible}
 * 404s on a null owner, locking a sub-agent out of its own booking. The booking quota would also
 * 403 an order the platform had already approved.
 *
 * <p>{@link #projectHotel} and {@link #withdrawHotel} DO run on the approval thread, wrapped in
 * {@code TenantScope.call(tenantId, …)}, and are idempotent so the compensating scheduler can replay
 * them.</p>
 */
public interface CrmBookingLinkPort {

    /**
     * Resolve an existing CRM booking the tenant wants this hotel attached to.
     *
     * <p>Tenant-scoped, sub-agent row-scoped, and refuses a terminal booking — attaching a hotel line
     * to a CANCELLED or REFUNDED booking is possible today only because the service-item layer has no
     * lifecycle guard of its own.</p>
     *
     * @throws com.crm.travelcrm.common.exception.ResourceNotFoundException on a foreign or unknown id
     */
    CrmBookingRef requireLinkable(UUID bookingPublicId, Long tenantId);

    /**
     * Read-only lookup for display — the booking code to show beside a marketplace order.
     *
     * <p>Separate from {@link #requireLinkable} on purpose: that one enforces attach-time rules and
     * throws on a terminal booking, which is exactly wrong for rendering history. A cancelled trip
     * still has a code, and the marketplace row that rode on it still has to name it.</p>
     *
     * <p>Exists so the marketplace never has to touch {@code BookingRepository} or the {@code Booking}
     * entity to read one string. Empty when unknown or foreign — never an exception, never the entity.</p>
     */
    Optional<CrmBookingRef> lookup(UUID bookingPublicId, Long tenantId);

    /**
     * Mint a CRM booking for a marketplace order that has no trip yet.
     *
     * <p>Goes through {@code BookingService.create()} so quota, booking-code sequencing, customer
     * resolution, financial derivation and the cancellation-policy pin all happen exactly once, in
     * one place.</p>
     */
    CrmBookingRef createForMarketplace(MarketplaceBookingSeed seed);

    /**
     * Idempotent UPSERT of the hotel's service line and its payable, keyed on
     * {@code marketplaceBookingPublicId}. Safe to call repeatedly — the compensating scheduler does.
     */
    CrmMarketplaceProjection projectHotel(MarketplaceHotelProjectionCommand cmd);

    /**
     * Reject / cancel. Flips the service line to CANCELLED and settles the payable.
     *
     * <p><b>Never touches {@code Booking.status}.</b> {@code BookingServiceImpl.cancel()} mints an
     * immutable cancellation record, issues a numbered credit note, freezes a P&amp;L and disposes of
     * the source lead — that is the customer cancelling their trip, not a supplier declining a room.
     */
    void withdrawHotel(UUID marketplaceBookingPublicId, Long tenantId, String reason);

    /**
     * The inverse signal: the CRM booking was cancelled, so any marketplace order riding on it must
     * be told. Without this the platform row stays CONFIRMED — room still held with the supplier —
     * and {@link #requireLinkable} then refuses the booking as terminal, leaving no path at all to
     * restate the payable.
     *
     * @return the marketplace booking publicIds that were attached, for the caller to act on
     */
    java.util.List<UUID> marketplaceBookingsOn(Long bookingId);

    // ── crossing types ──────────────────────────────────────────────────────

    /** What the marketplace learns about a CRM booking. Never the entity. */
    record CrmBookingRef(UUID bookingPublicId, String bookingCode, UUID customerPublicId, Long ownerUserId) {}

    /**
     * Everything needed to mint a booking for a marketplace order.
     *
     * @param customerSellingAmount the TENANT's price to their own customer — their number, not ours
     * @param tenantPayableAmount   the SERVER-computed payable, read off the persisted platform row,
     *                              never off the request. Lands in {@code Booking.vendorCost}, which
     *                              {@code ExpenseCostType} documents as already holding everything
     *                              paid to suppliers.
     */
    record MarketplaceBookingSeed(
            Long tenantId,
            UUID marketplaceBookingPublicId,
            CustomerResolveRequest customer,
            String destination,
            LocalDate bookingDate,
            LocalDate travelDate,
            BigDecimal customerSellingAmount,
            BigDecimal tenantPayableAmount,
            Boolean overseasTourPackage,
            UUID leadPublicId,
            UUID assignedUserPublicId) {}

    /**
     * The hotel line to project onto a booking.
     *
     * @param title required — {@code BookingServiceItem.title} is NOT NULL
     * @param tenantPayableAmount goes to the VENDOR expense and into {@code Booking.vendorCost};
     *                            NEVER to {@code BookingServiceItem.cost}, because any priced item
     *                            flips the GST tax invoice to per-item lines and bills only those,
     *                            under-billing tax on the rest of the package
     */
    record MarketplaceHotelProjectionCommand(
            Long tenantId,
            UUID bookingPublicId,
            UUID marketplaceBookingPublicId,
            String title,
            String hotelName,
            String cityName,
            String roomName,
            String mealPlanName,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer rooms,
            ServiceItemStatus status,
            String confirmationNumber,
            BigDecimal tenantPayableAmount,
            LocalDate payableDueDate,
            String notes) {}

    /** What the projection wrote, so the platform row can record the link. */
    record CrmMarketplaceProjection(UUID serviceItemPublicId, UUID expensePublicId) {}
}
