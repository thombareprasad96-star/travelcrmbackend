package com.crm.travelcrm.subagent.enums;

/**
 * How a sub-agent's COMMISSION rate is expressed (the enum is named {@code MarkupType} for legacy
 * reasons — the value is the broker commission rate, not an additive markup). Mirrors the quotation
 * {@code DiscountType} pattern.
 */
public enum MarkupType {
    /** {@code markupValue} is a percentage (0–100) of the booking sale value. */
    PERCENT,
    /** {@code markupValue} is a flat commission amount per booking. */
    FIXED
}
