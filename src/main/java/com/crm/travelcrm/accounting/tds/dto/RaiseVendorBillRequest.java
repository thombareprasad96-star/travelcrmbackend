package com.crm.travelcrm.accounting.tds.dto;

import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Raise a vendor bill (payable). TDS is computed from {@code tdsSection} + the vendor's PAN. */
@Getter
@Setter
public class RaiseVendorBillRequest {

    @NotNull
    private UUID vendorPublicId;

    /** Optional booking this cost belongs to. */
    private UUID bookingPublicId;

    /** Optional booking service line this bill settles. */
    private UUID serviceItemPublicId;

    /** Vendor's own invoice/bill reference. */
    private String billNumber;

    private LocalDate billDate;

    private String description;

    @NotNull
    @Positive
    private BigDecimal grossAmount;

    /** Input GST embedded in the gross (recoverable as ITC when eligible). Null → 0. */
    private BigDecimal gstInput;

    /** Amount TDS is computed on. Null → grossAmount − gstInput. */
    private BigDecimal tdsBase;

    /** Null → no TDS deducted on this bill. */
    private TdsSection tdsSection;
}