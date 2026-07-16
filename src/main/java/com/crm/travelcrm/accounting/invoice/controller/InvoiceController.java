package com.crm.travelcrm.accounting.invoice.controller;

import com.crm.travelcrm.accounting.invoice.dto.CancelInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.IssueInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.accounting.invoice.service.InvoiceService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounting/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_MANAGE')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> issue(
            @Valid @RequestBody IssueInvoiceRequest request,
            @AuthenticationPrincipal User currentUser) {
        TaxInvoiceResponse issued = invoiceService.issue(request, currentUser.getTenantId(), currentUser.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Invoice issued: " + issued.getInvoiceNumber(), issued, 201));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<PagedApiResponse<TaxInvoiceResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<TaxInvoiceResponse> result = invoiceService.list(currentUser.getTenantId(), pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Invoices retrieved", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> get(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Invoice retrieved", invoiceService.get(publicId, currentUser.getTenantId())));
    }

    @GetMapping("/booking/{bookingPublicId}")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<List<TaxInvoiceResponse>>> listForBooking(
            @PathVariable UUID bookingPublicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Booking invoices retrieved",
                invoiceService.listForBooking(bookingPublicId, currentUser.getTenantId())));
    }

    @GetMapping("/{publicId}/pdf")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<byte[]> pdf(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        byte[] bytes = invoiceService.renderPdf(publicId, currentUser.getTenantId());
        TaxInvoiceResponse inv = invoiceService.get(publicId, currentUser.getTenantId());
        String filename = "Invoice-" + inv.getInvoiceNumber().replace('/', '_') + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_MANAGE')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> cancel(
            @PathVariable UUID publicId,
            @Valid @RequestBody CancelInvoiceRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success("Invoice cancelled",
                invoiceService.cancel(publicId, request.getReason(),
                        currentUser.getTenantId(), currentUser.getEmail())));
    }

    @PostMapping("/{publicId}/einvoice")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_MANAGE')")
    public ResponseEntity<ApiResponse<TaxInvoiceResponse>> generateEInvoice(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success("e-invoice processed",
                invoiceService.generateEInvoice(publicId, currentUser.getTenantId())));
    }
}