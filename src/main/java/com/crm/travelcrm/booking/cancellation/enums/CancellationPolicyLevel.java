package com.crm.travelcrm.booking.cancellation.enums;

/**
 * The scope a {@link com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy} governs.
 * Resolution is most-specific-wins: a {@code PACKAGE} policy (attached to a quotation/template)
 * overrides the tenant's {@code COMPANY} default.
 */
public enum CancellationPolicyLevel {

    /** The tenant-wide default. Exactly one active version at a time (owner is null). */
    COMPANY,

    /** Attached to a specific package (its owner is the QuotationTemplate/Quotation publicId). */
    PACKAGE
}