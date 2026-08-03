package com.crm.travelcrm.fleet.enums;

/**
 * How an expense behaves in the tenant's own books — the field that makes fleet money visible to
 * {@code AccountingReportService} instead of sitting in a parallel universe.
 *
 * <p>The standalone plan's expense table had no tax fields at all. Since P&amp;L there is computed
 * strictly as {@code TaxInvoice − VendorBill}, every rupee of fleet cost would have been invisible
 * to the customer's own profit figure and GST summary — an odd outcome for an Indian fleet product.
 *
 * <p>This is a <em>classification</em>, not a computation. The product records what an expense is;
 * it does not calculate anyone's tax liability, file a return, or compute depreciation. The
 * compliance practitioner was explicit that software attempting any of those is a liability.
 */
public enum FleetTaxCharacter {

    /** Ordinary revenue expenditure — fuel, tolls, parking, servicing, driver allowance. */
    ALLOWABLE("Allowable"),

    /**
     * Not deductible under s.37(1) — the classic case being a traffic challan, which is a penalty
     * for an infraction of law. It is a real cost to the business and must appear in the operating
     * total; it simply cannot be claimed against taxable profit.
     */
    DISALLOWABLE_37_1("Disallowable (s.37(1))"),

    /**
     * Capitalised rather than expensed — a new set of tyres, an engine rebuild, a fitted accessory.
     * Belongs on the asset, not in the month's running cost, or the vehicle's cost/km spikes in
     * whichever month the work happened to fall.
     */
    CAPITAL("Capital");

    private final String label;

    FleetTaxCharacter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Default classification for a type, overridable per row by whoever holds the money authority. */
    public static FleetTaxCharacter defaultFor(FleetExpenseType type) {
        return switch (type) {
            case CHALLAN -> DISALLOWABLE_37_1;
            case TYRE    -> CAPITAL;
            default      -> ALLOWABLE;
        };
    }
}
