package com.crm.travelcrm.booking.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Result of recording a refund disbursement. Every figure is the backend's — the UI just displays it,
 * then offers the freshly-issued refund voucher for download.
 */
@Getter
@Builder
public class RefundResponseDTO {

    private UUID       bookingPublicId;
    private UUID       paymentPublicId;

    /** This disbursement. */
    private BigDecimal amount;
    private String     method;
    private String     reference;
    private LocalDate  refundDate;

    /** Cumulative and remaining, against the frozen refund due. */
    private BigDecimal refundDue;
    private BigDecimal totalRefunded;
    private BigDecimal remainingRefundable;

    /** PENDING | PARTIALLY_PAID | PAID. */
    private String     refundStatus;
    private boolean    fullyRefunded;

    /** The refund voucher issued for this disbursement. */
    private String     refundVoucherNumber;
    private UUID       refundVoucherDocPublicId;
}
