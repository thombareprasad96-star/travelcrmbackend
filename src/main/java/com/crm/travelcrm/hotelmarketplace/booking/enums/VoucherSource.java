package com.crm.travelcrm.hotelmarketplace.booking.enums;

/**
 * Where a hotel voucher's PDF comes from.
 *
 * <p>Design §7 offers two sources and lists the upload first, which is the right order for an
 * ON_REQUEST model: the operator confirms by talking to the hotel, and the hotel usually sends its
 * own voucher back. A system-rendered document is the fallback for when it does not.</p>
 */
public enum VoucherSource {

    /** Rendered on the fly from the confirmed snapshot. Nothing is stored. */
    GENERATED,

    /**
     * A PDF or image the hotel supplied, stored as bytes in {@code platform_hotel_voucher_files}.
     *
     * <p>Postgres, never a public CDN. The document names the guest, their dates and their
     * confirmation number — the same reasoning that keeps traveler documents out of Cloudinary.</p>
     */
    UPLOADED
}
