package com.crm.travelcrm.accounting.settings.dto;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import lombok.Getter;
import lombok.Setter;

/** Partial update of the tenant's accounting/GST settings — null fields are left unchanged. */
@Getter
@Setter
public class UpdateAccountingSettingsRequest {

    private GstScheme gstScheme;
    private Boolean autoTcsOnOverseas;
    private Boolean roundInvoiceTotal;
    private Boolean inputTaxCreditEligible;
}