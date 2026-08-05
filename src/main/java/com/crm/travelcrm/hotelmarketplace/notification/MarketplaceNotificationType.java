package com.crm.travelcrm.hotelmarketplace.notification;

/**
 * Type and reference-type constants for the marketplace notifications a TENANT receives.
 *
 * <p>Constants on a final class rather than an enum, matching {@code PlatformNotificationType} and
 * how {@code Notification.type} is already stored — a free-form {@code VARCHAR(100)}. A new event
 * type therefore never needs a schema change or a {@code *_check} constraint refresh.
 *
 * <p><b>Every type deliberately contains the token {@code BOOKING}.</b> The staff Navbar picks the
 * colour of a notification's dot by SUBSTRING match on the type — {@code BOOKING} / {@code PAYMENT}
 * / {@code LEAD} / {@code REMIND} — and anything matching none of them renders in the neutral slate
 * that reads as background noise. That is the only reason the voucher event is
 * {@code HOTEL_BOOKING_VOUCHER_ISSUED} rather than the shorter {@code HOTEL_VOUCHER_ISSUED}.
 */
public final class MarketplaceNotificationType {

    private MarketplaceNotificationType() {}

    // ── The SuperAdmin's decision ────────────────────────────────────────────────────────────
    public static final String HOTEL_BOOKING_CONFIRMED         = "HOTEL_BOOKING_CONFIRMED";
    public static final String HOTEL_BOOKING_REJECTED          = "HOTEL_BOOKING_REJECTED";

    // ── Price revision (§8 Step 6B) — the tenant owes an answer, and it can go stale ──────────
    public static final String HOTEL_BOOKING_REVISION_REQUIRED = "HOTEL_BOOKING_REVISION_REQUIRED";
    public static final String HOTEL_BOOKING_REVISION_EXPIRED  = "HOTEL_BOOKING_REVISION_EXPIRED";

    // ── After confirmation ───────────────────────────────────────────────────────────────────
    /** A cancellation charge is on the table and the tenant has to accept it before it binds. */
    public static final String HOTEL_BOOKING_CANCELLATION_QUOTED = "HOTEL_BOOKING_CANCELLATION_QUOTED";
    public static final String HOTEL_BOOKING_CANCELLED         = "HOTEL_BOOKING_CANCELLED";
    public static final String HOTEL_BOOKING_VOUCHER_ISSUED    = "HOTEL_BOOKING_VOUCHER_ISSUED";

    /**
     * The room is held with the supplier but the CRM projection has not landed. Operational, not
     * commercial: the tenant is told their booking is safe and simply not visible yet, so nobody
     * re-books a room they already hold.
     */
    public static final String HOTEL_BOOKING_SYNC_FAILED       = "HOTEL_BOOKING_SYNC_FAILED";

    /**
     * Reference-type discriminator — deliberately the plain {@code "BOOKING"}, not a marketplace
     * value. {@code notifications.reference_type} carries a CHECK constraint from the V1 baseline
     * limited to LEAD/BOOKING/REMINDER/CUSTOMER/VENDOR, and {@code NotificationReferenceType
     * .fromString} quietly maps anything outside it to {@code null} — which the Navbar reads as "not
     * clickable". A marketplace-specific value would cost a migration and buy an unclickable row.
     */
    public static final String REF_HOTEL_BOOKING               = "BOOKING";
}
