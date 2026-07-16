package com.crm.travelcrm.accounting.invoice.dto;

import com.crm.travelcrm.accounting.invoice.enums.InvoiceStatus;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Full invoice detail (list rows send the same shape with {@code lines} omitted). */
@Getter
@Builder
public class TaxInvoiceResponse {

    private final UUID publicId;
    private final String invoiceNumber;
    private final InvoiceType invoiceType;
    private final String invoiceTypeLabel;
    private final InvoiceStatus status;
    private final LocalDate invoiceDate;
    private final String financialYear;

    private final UUID bookingPublicId;
    private final String bookingCode;

    private final String supplierGstin;
    private final String supplierStateCode;
    private final String recipientName;
    private final String recipientGstin;
    private final String recipientStateCode;
    private final String placeOfSupplyCode;
    private final SupplyType supplyType;
    private final boolean reverseCharge;
    private final boolean overseasTourPackage;

    private final BigDecimal taxableValue;
    private final BigDecimal cgst;
    private final BigDecimal sgst;
    private final BigDecimal igst;
    private final BigDecimal cess;
    private final BigDecimal tcs;
    private final BigDecimal roundOff;
    private final BigDecimal invoiceTotal;

    private final String einvoiceStatus;
    private final String irn;

    private final LocalDateTime issuedAt;
    private final String issuedByEmail;
    private final LocalDateTime cancelledAt;
    private final String cancelReason;
    private final String creditNoteNumber;

    private final List<TaxInvoiceLineResponse> lines;
}