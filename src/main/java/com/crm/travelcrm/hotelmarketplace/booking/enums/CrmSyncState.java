package com.crm.travelcrm.hotelmarketplace.booking.enums;

/**
 * How far the tenant-side CRM projection of a CONFIRMED booking has got.
 *
 * <p>This exists because the platform write and the CRM write <b>cannot share a transaction</b>:
 * {@code TenantScope} refuses to be entered inside an active transaction, by design, since it swaps
 * a ThreadLocal that a half-committed transaction would then observe. So the two are separate
 * transactions and the gap is closed by compensation, not rollback — this column is the compensation
 * marker, and {@code MarketplaceCrmSyncScheduler} drains it.</p>
 *
 * <p>The failure mode it buys: "confirmed on the platform, not yet visible in the CRM", which is
 * recoverable and visible. What it avoids: a confirmation that silently never happened.</p>
 */
public enum CrmSyncState {

    /** Confirmed, projection not yet attempted or not yet successful. The scheduler will retry. */
    PENDING,

    /** Service line and payable are in the tenant's CRM and match the platform row. */
    SYNCED,

    /** Last attempt failed. Retried with a cap — see {@link #ABANDONED}. */
    FAILED,

    /**
     * Retries exhausted. Deliberately a distinct terminal state rather than leaving it FAILED
     * forever: a deterministic failure (a 4xx the input will always produce) would otherwise be
     * replayed on every tick indefinitely, which is a poison pill dressed up as resilience.
     * Needs an operator.
     */
    ABANDONED
}
