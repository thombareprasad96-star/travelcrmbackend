package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.accounting.invoice.dto.IssueInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.accounting.invoice.service.InvoiceService;
import com.crm.travelcrm.booking.dto.request.BookingIssueInvoiceRequest;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Delegates every operation to the accounting {@link InvoiceService} so the booking's GST invoice IS
 * the accountant's invoice — no parallel numbering, calc or PDF here. Tenant scoping + booking→invoice
 * resolution live in the accounting service; this layer only pins the booking from the path and
 * re-checks ownership so one booking can never touch another's invoice.
 */
@Service
@RequiredArgsConstructor
public class BookingGstInvoiceServiceImpl implements BookingGstInvoiceService {

    private final InvoiceService invoiceService;

    @Override
    public List<TaxInvoiceResponse> list(UUID bookingPublicId, Long tenantId) {
        return invoiceService.listForBooking(bookingPublicId, tenantId);
    }

    @Override
    public TaxInvoiceResponse issue(UUID bookingPublicId, BookingIssueInvoiceRequest request,
                                    Long tenantId, String userEmail) {
        IssueInvoiceRequest accountingRequest = new IssueInvoiceRequest();
        accountingRequest.setBookingPublicId(bookingPublicId);   // from the path — never the body
        accountingRequest.setInvoiceType(request.getInvoiceType());
        accountingRequest.setInvoiceDate(request.getInvoiceDate());
        accountingRequest.setOverseasTourPackage(request.getOverseasTourPackage());
        accountingRequest.setRecipientGstin(request.getRecipientGstin());
        accountingRequest.setPlaceOfSupplyState(request.getPlaceOfSupplyState());
        return invoiceService.issue(accountingRequest, tenantId, userEmail);
    }

    @Override
    public TaxInvoiceResponse get(UUID bookingPublicId, UUID invoicePublicId, Long tenantId) {
        TaxInvoiceResponse invoice = invoiceService.get(invoicePublicId, tenantId);
        if (invoice.getBookingPublicId() == null || !invoice.getBookingPublicId().equals(bookingPublicId)) {
            throw new ResourceNotFoundException("Invoice not found on this booking: " + invoicePublicId);
        }
        return invoice;
    }

    @Override
    public byte[] renderPdf(UUID bookingPublicId, UUID invoicePublicId, Long tenantId) {
        get(bookingPublicId, invoicePublicId, tenantId);   // ownership guard
        return invoiceService.renderPdf(invoicePublicId, tenantId);
    }

    @Override
    public TaxInvoiceResponse cancel(UUID bookingPublicId, UUID invoicePublicId, String reason,
                                     Long tenantId, String userEmail) {
        get(bookingPublicId, invoicePublicId, tenantId);   // ownership guard
        return invoiceService.cancel(invoicePublicId, reason, tenantId, userEmail);
    }
}