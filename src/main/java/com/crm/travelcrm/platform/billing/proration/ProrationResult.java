package com.crm.travelcrm.platform.billing.proration;

import java.math.BigDecimal;

/**
 * Outcome of a mid-cycle plan-change proration. {@code amount} is the SIGNED delta for the remaining
 * days of the period: {@code > 0} = extra to charge (upgrade), {@code < 0} = credit back (downgrade),
 * {@code 0} = no adjustment. {@code remainingDays}/{@code periodDays} are carried for the invoice note.
 */
public record ProrationResult(BigDecimal amount, long remainingDays, long periodDays) {

    public boolean isCharge() {
        return amount.signum() > 0;
    }

    public boolean isCredit() {
        return amount.signum() < 0;
    }

    public boolean hasAdjustment() {
        return amount.signum() != 0;
    }
}