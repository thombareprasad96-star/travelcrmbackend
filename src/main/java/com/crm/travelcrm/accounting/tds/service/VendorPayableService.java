package com.crm.travelcrm.accounting.tds.service;
import com.crm.travelcrm.accounting.tds.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VendorPayableService {

    VendorBillResponse raiseBill(RaiseVendorBillRequest request, Long tenantId, String userEmail);

    Page<VendorBillResponse> list(Long tenantId, Pageable pageable);

    VendorBillResponse get(UUID publicId, Long tenantId);

    VendorBillResponse recordPayment(UUID billPublicId, RecordVendorPaymentRequest request,
                                     Long tenantId, String userEmail);

    VendorBillResponse cancelBill(UUID publicId, String reason, Long tenantId, String userEmail);

    List<TdsSummaryRow> tdsSummary(Long tenantId, LocalDate from, LocalDate to);
}