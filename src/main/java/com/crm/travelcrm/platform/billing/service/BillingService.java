package com.crm.travelcrm.platform.billing.service;

import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.dto.CreateBillingRequest;

import java.util.List;
import java.util.UUID;

/** Platform billing (SuperAdmin → tenant invoices). */
public interface BillingService {

    List<BillingRecordResponse> listForTenant(UUID tenantPublicId);

    BillingRecordResponse create(UUID tenantPublicId, CreateBillingRequest request);

    BillingRecordResponse markPaid(UUID billingPublicId);

    BillingRecordResponse markUnpaid(UUID billingPublicId);
}