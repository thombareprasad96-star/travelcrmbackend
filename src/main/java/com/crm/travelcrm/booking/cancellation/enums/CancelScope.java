package com.crm.travelcrm.booking.cancellation.enums;

/**
 * The extent of a cancellation. v1 supports whole-booking cancellation only; {@code PARTIAL} is
 * reserved in the schema so partial cancellation (e.g. some pax drop out) can be added later without
 * a migration break. The current booking model carries no pax count and its service-item costs do
 * not drive booking totals, so partial cancellation cannot be computed correctly yet — it is
 * deliberately not implemented rather than approximated.
 */
public enum CancelScope {
    FULL,
    PARTIAL   // reserved — not implemented in v1
}