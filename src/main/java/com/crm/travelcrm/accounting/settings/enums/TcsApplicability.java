package com.crm.travelcrm.accounting.settings.enums;

/**
 * When a tenant collects TCS on a booking — the tenant's own call, not a platform-wide constant.
 *
 * <p>TCS on an overseas tour programme package is a statutory collection the agency makes on the
 * government's behalf (historically s.206C(1G) of the Income-tax Act 1961; since 1 Apr 2026,
 * s.394(1) of the Income-tax Act 2025). It is <b>never the agency's revenue or profit</b> — it is
 * the customer's tax, held briefly and remitted, and it shows in the customer's Form 26AS against
 * their own PAN.
 *
 * <p>Whether it applies at all is a per-tenant, per-booking question that this platform cannot
 * answer centrally:
 * <ul>
 *   <li>A purely <b>domestic</b> operator collects none of it — the section does not reach domestic
 *       packages, so a flat platform-wide rate silently over-collects tax from every customer.</li>
 *   <li>An operator selling overseas packages collects it, but only on those bookings, and only when
 *       the package bundles at least two of {international ticket, hotel, other similar expenditure}
 *       (CBDT Circular 10/2023). A bare air ticket is not an overseas tour programme package.</li>
 *   <li>Some tenants deliberately handle it outside the CRM, in their accounting software.</li>
 * </ul>
 *
 * <p>So the tenant chooses, and the CRM applies exactly what it is told.
 */
public enum TcsApplicability {

    /**
     * Never add TCS to a booking. The correct setting for a purely domestic operator, and for any
     * tenant that collects TCS outside this system.
     */
    NEVER,

    /**
     * Add TCS only to bookings flagged as an overseas tour package
     * ({@code Booking.overseasTourPackage}). This is the setting that matches the statute for an
     * agency selling both domestic and outbound.
     */
    OVERSEAS_ONLY,

    /**
     * Add TCS to every booking regardless of destination.
     *
     * <p>This is the DEFAULT purely for backward compatibility: before booking tax became
     * per-tenant, the platform applied a flat rate to every booking unconditionally, and defaulting
     * to anything else would silently change the amount payable on existing bookings the moment
     * their totals were recomputed. It is <b>not</b> a recommendation — a tenant selling domestic
     * packages should move to {@link #NEVER} or {@link #OVERSEAS_ONLY}, which is a decision for
     * them and their CA, not for this code.
     */
    ALWAYS
}
