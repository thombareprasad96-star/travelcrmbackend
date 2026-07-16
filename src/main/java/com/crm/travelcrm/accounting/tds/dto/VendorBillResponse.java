package com.crm.travelcrm.accounting.tds.dto;

import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import com.crm.travelcrm.accounting.tds.enums.VendorBillStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class VendorBillResponse {

    private final UUID publicId;
    private final UUID vendorPublicId;
    private final String vendorName;
    private final String panSnapshot;
    private final String gstinSnapshot;
    private final String bookingCode;
    private final String billNumber;
    private final LocalDate billDate;
    private final String description;

    private final BigDecimal grossAmount;
    private final BigDecimal gstInput;
    private final BigDecimal tdsBase;
    private final TdsSection tdsSection;
    private final String tdsSectionLabel;
    private final BigDecimal tdsRatePct;
    private final BigDecimal tdsAmount;
    private final BigDecimal netPayable;
    private final BigDecimal amountPaid;
    private final BigDecimal balancePayable;
    private final VendorBillStatus status;

    private final LocalDateTime cancelledAt;
    private final String cancelReason;

    private final List<VendorPaymentResponse> payments;
}