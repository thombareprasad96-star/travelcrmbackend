package com.crm.travelcrm.accounting.tax.enums;

/**
 * Whether a supply is within the supplier's state (CGST + SGST) or across states (IGST), determined
 * by comparing the supplier's GST state code with the place-of-supply state code.
 */
public enum SupplyType {
    /** Supplier state == place of supply → CGST + SGST (each half the GST rate). */
    INTRA_STATE,
    /** Supplier state != place of supply (or export/unknown) → IGST (the full GST rate). */
    INTER_STATE
}