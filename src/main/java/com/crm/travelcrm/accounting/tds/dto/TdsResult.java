package com.crm.travelcrm.accounting.tds.dto;

import java.math.BigDecimal;

/** The rate (as a percent, e.g. 2.00) and rupee amount of TDS to withhold on a payment. */
public record TdsResult(BigDecimal ratePct, BigDecimal amount) {

    public static TdsResult none() {
        return new TdsResult(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}