package com.crm.travelcrm.subagent.enums;

/** How a sub-agent's markup is expressed. Mirrors the quotation DiscountType pattern. */
public enum MarkupType {
    /** {@code markupValue} is a percentage (0–100) of the quotation subtotal. */
    PERCENT,
    /** {@code markupValue} is a flat amount added to the quotation total. */
    FIXED
}
