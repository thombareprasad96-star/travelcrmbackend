package com.crm.travelcrm.accounting.invoice.service;

import com.crm.travelcrm.accounting.invoice.dto.IssueInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface InvoiceService {

    TaxInvoiceResponse issue(IssueInvoiceRequest request, Long tenantId, String userEmail);

    TaxInvoiceResponse get(UUID publicId, Long tenantId);

    Page<TaxInvoiceResponse> list(Long tenantId, Pageable pageable);

    List<TaxInvoiceResponse> listForBooking(UUID bookingPublicId, Long tenantId);

    TaxInvoiceResponse cancel(UUID publicId, String reason, Long tenantId, String userEmail);

    /** Frozen PDF bytes — rendered from the snapshot and frozen on first fetch. */
    byte[] renderPdf(UUID publicId, Long tenantId);

    /** Attempt IRN registration via the configured e-invoice provider (stub → UNAVAILABLE). */
    TaxInvoiceResponse generateEInvoice(UUID publicId, Long tenantId);
}