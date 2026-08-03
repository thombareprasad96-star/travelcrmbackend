package com.crm.travelcrm.hotelmarketplace.booking.enums;

/**
 * Whether the guest-facing hotel voucher has been issued.
 *
 * <p><b>Deliberately a second axis, not more values on {@link MarketplaceBookingStatus}</b> (design
 * doc §5.11, §7). A confirmed booking is a commitment to a supplier; a voucher is a document. If the
 * two shared one field, a PDF that failed to render — or one an operator revoked to reissue with a
 * corrected guest name — would have to be expressed as a change to the booking's state, and the room
 * the hotel is already holding would appear to un-confirm itself.</p>
 */
public enum VoucherStatus {

    /** Confirmed, but the document has not been produced yet. The default for every new row. */
    NOT_ISSUED,

    /** A voucher exists and the tenant may download it. */
    ISSUED,

    /**
     * Withdrawn — reissued with corrections, or the booking was cancelled after issue. The row is
     * kept rather than reset to {@code NOT_ISSUED} so "this was once issued" stays legible; a guest
     * may still be holding the old copy.
     */
    REVOKED;

    public boolean isDownloadable() {
        return this == ISSUED;
    }
}
