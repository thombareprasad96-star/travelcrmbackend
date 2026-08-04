package com.crm.travelcrm.hotelmarketplace.commission.dto;

import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One ledger row as the SuperAdmin console renders it.
 *
 * <p><b>There is no tenant counterpart to this class and there must not be one.</b> Every row exposes
 * {@link #supplierTotal} and the platform's margin ({@link #amount}); a tenant view of the earning
 * ledger is a view of what the platform makes on them.</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommissionEntryDto {

    UUID publicId;

    UUID hotelBookingPublicId;
    String bookingCode;

    Long tenantId;
    String tenantCode;

    CommissionEntryType entryType;
    CommissionEntryStatus status;

    /** Signed: negative on a reversal, and on an adjustment that writes earning down. */
    BigDecimal amount;
    String currency;

    /** The economics the row was derived from, as snapshotted — not re-read from the booking. */
    BigDecimal supplierTotal;
    BigDecimal tenantPayable;

    LocalDate effectiveDate;
    String reason;
    String referenceKey;

    LocalDateTime createdAt;
    String createdBy;
}
