package com.crm.travelcrm.hotelmarketplace.voucher.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * Everything the guest-facing hotel voucher is allowed to say.
 *
 * <p><b>There is no money field on this class, and that is the design.</b> Design §7 forbids the
 * voucher from carrying supplier net, platform commission, internal notes or contract data, and a
 * document a GUEST holds must also not carry the tenant's own selling price or what the tenant owes
 * the platform. Handing {@code PlatformHotelBooking} straight to the template would leave every one
 * of those a single {@code doc.supplierTotal} away from being printed — so the template is given a
 * model that physically cannot express them. Same reasoning as
 * {@code MarketplaceBookingMapper}: the safety comes from the type, not from reviewer discipline.</p>
 */
@Getter
@Builder
public class MarketplaceVoucherModel {

    // ── Document identity ───────────────────────────────────────────────────

    /** Platform booking number the supplier and the support desk both recognise. */
    private final String bookingCode;

    private final String voucherNumber;

    /** The hotel's own reference. What the front desk actually looks up on arrival. */
    private final String supplierConfirmationNumber;

    private final LocalDate issuedOn;

    /**
     * Whether this is a live voucher or a SuperAdmin preview/withdrawn copy. Drives the stamp, so a
     * PDF pulled before issuance can never be mistaken for the real document at a hotel desk.
     */
    private final boolean issued;

    /** "Confirmed", "Not Issued — Preview" or "Revoked". Rendered verbatim into the stamp. */
    private final String statusLabel;

    // ── The stay ────────────────────────────────────────────────────────────

    private final String hotelName;
    private final String address;
    private final String cityName;
    private final String countryCode;

    private final String roomName;
    private final String mealPlan;

    private final LocalDate checkIn;
    private final LocalDate checkOut;
    private final Integer nights;
    private final Integer rooms;
    private final Integer adults;
    private final Integer children;

    // ── The guest ───────────────────────────────────────────────────────────

    private final String leadGuestName;
    private final String leadGuestPhone;
    private final String leadGuestEmail;
    private final String specialRequests;

    /** Cancellation and usage instructions, as snapshotted at confirmation. */
    private final String cancellationTerms;

    // ── Who to call ─────────────────────────────────────────────────────────

    private final String platformName;
    private final String platformTagline;
    private final String platformSupportPhone;
    private final String platformSupportEmail;
    private final String platformWebsite;
    private final String platformLogoUrl;
}
