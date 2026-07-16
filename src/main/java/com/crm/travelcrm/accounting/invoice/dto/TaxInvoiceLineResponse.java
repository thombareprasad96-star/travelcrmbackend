package com.crm.travelcrm.accounting.invoice.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class TaxInvoiceLineResponse {

    private final int lineNo;
    private final String description;
    private final String hsnSac;
    private final BigDecimal taxableValue;
    private final BigDecimal gstRatePct;
    private final BigDecimal cgstAmt;
    private final BigDecimal sgstAmt;
    private final BigDecimal igstAmt;
    private final BigDecimal cessAmt;
    private final BigDecimal lineTotal;
}