package com.crm.travelcrm.hotelmarketplace.booking.enums;

import java.util.Set;

/**
 * Lifecycle of a tenant's request to book a catalog hotel through the platform.
 *
 * <p><b>A tenant can never reach {@link #CONFIRMED}.</b> Only a SuperAdmin approval performs that
 * transition, after verifying availability and price with the hotel — which is the whole point of an
 * ON_REQUEST model: confirming against inventory nobody maintains is how you sell a room that does
 * not exist (design doc §8, §19).</p>
 */
public enum MarketplaceBookingStatus {

    /** Submitted by the tenant, waiting in the SuperAdmin queue. No commission, no voucher. */
    REQUESTED,

    /** A SuperAdmin has picked it up and is checking with the supplier. */
    UNDER_REVIEW,

    /**
     * Availability exists but the price or terms moved. The tenant must accept the new amount before
     * anything is confirmed — silently approving a higher payable is not the platform's call.
     */
    TENANT_APPROVAL_REQUIRED,

    /** The tenant accepted the revision; back to the SuperAdmin for final approval. */
    TENANT_ACCEPTED,

    /** Confirmed with the supplier. The only state that projects into the tenant's CRM. */
    CONFIRMED,

    /** Unavailable, refused, or the revision was declined. */
    REJECTED,

    /** The tenant asked to cancel a confirmed booking; the SuperAdmin works it with the supplier. */
    CANCEL_REQUESTED,

    CANCELLED,

    /** Never actioned within its validity. */
    EXPIRED;

    /**
     * States a SuperAdmin may approve from. Anything else is either not ready or already decided.
     *
     * <p>{@link #TENANT_APPROVAL_REQUIRED} is <b>deliberately absent</b>. That is the entire point of
     * the revision flow: an open offer the tenant has not answered must not be confirmable, or a
     * SuperAdmin could raise the price and approve it in the same breath (§8 Step 6B).</p>
     */
    private static final Set<MarketplaceBookingStatus> APPROVABLE =
            Set.of(REQUESTED, UNDER_REVIEW, TENANT_ACCEPTED);

    /** Decided one way or the other — no further transition. */
    private static final Set<MarketplaceBookingStatus> TERMINAL =
            Set.of(REJECTED, CANCELLED, EXPIRED);

    /**
     * States from which a SuperAdmin may put a revised price to the tenant.
     *
     * <p>Includes the two states that already carry a revision: the supplier can move twice, and an
     * operator who fat-fingers an amount needs a way to correct it that is not "reject and ask the
     * tenant to start again". A fresh revision replaces the open offer.</p>
     */
    private static final Set<MarketplaceBookingStatus> REVISABLE =
            Set.of(REQUESTED, UNDER_REVIEW, TENANT_APPROVAL_REQUIRED, TENANT_ACCEPTED);

    /**
     * States a tenant may withdraw outright, with no supplier conversation.
     *
     * <p>Nothing has been committed to a hotel yet, so there is nothing to negotiate — this goes
     * straight to {@link #CANCELLED} rather than through {@link #CANCEL_REQUESTED}. Distinct from
     * {@link #REJECTED}, which means the <i>platform</i> refused.</p>
     */
    private static final Set<MarketplaceBookingStatus> TENANT_WITHDRAWABLE =
            Set.of(REQUESTED, UNDER_REVIEW, TENANT_APPROVAL_REQUIRED, TENANT_ACCEPTED);

    public boolean isApprovable() {
        return APPROVABLE.contains(this);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Whether the tenant still owes an answer before the SuperAdmin can move. */
    public boolean awaitsTenant() {
        return this == TENANT_APPROVAL_REQUIRED;
    }

    public boolean isRevisable() {
        return REVISABLE.contains(this);
    }

    /** Withdrawable by the tenant with no supplier round-trip, because nothing is confirmed. */
    public boolean isTenantWithdrawable() {
        return TENANT_WITHDRAWABLE.contains(this);
    }

    /**
     * A confirmed room exists with the supplier, so the tenant may only <i>ask</i> to cancel — the
     * charge depends on the snapshotted policy and on what the hotel actually allows.
     */
    public boolean isCancelRequestable() {
        return this == CONFIRMED;
    }

    /** States a SuperAdmin may settle a cancellation from. */
    public boolean isAdminCancellable() {
        return this == CONFIRMED || this == CANCEL_REQUESTED;
    }

    /**
     * Whether the platform has committed to a supplier. Governs commission accrual, voucher issue,
     * and whether a cancellation costs anything.
     */
    public boolean isCommitted() {
        return this == CONFIRMED || this == CANCEL_REQUESTED;
    }
}
