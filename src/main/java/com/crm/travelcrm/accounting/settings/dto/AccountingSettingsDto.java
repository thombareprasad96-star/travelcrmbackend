package com.crm.travelcrm.accounting.settings.dto;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import lombok.Builder;
import lombok.Getter;

/** Read view of the tenant's accounting/GST settings. */
@Getter
@Builder
public class AccountingSettingsDto {

    private final GstScheme gstScheme;
    /** Derived: true only when the scheme can raise a GST Tax Invoice (REGULAR). */
    private final boolean canIssueTaxInvoice;
    private final boolean autoTcsOnOverseas;
    private final boolean roundInvoiceTotal;
    private final boolean inputTaxCreditEligible;
    /** The tenant's own GSTIN from the Company profile, echoed for convenience (may be null). */
    private final String supplierGstin;
    private final String supplierStateCode;
}