package com.crm.travelcrm.accounting.tds.controller;

import com.crm.travelcrm.accounting.tds.dto.*;
import com.crm.travelcrm.accounting.tds.service.VendorPayableService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounting/vendor-bills")
@RequiredArgsConstructor
public class VendorPayableController {

    private final VendorPayableService service;

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_MANAGE')")
    public ResponseEntity<ApiResponse<VendorBillResponse>> raise(
            @Valid @RequestBody RaiseVendorBillRequest request,
            @AuthenticationPrincipal User currentUser) {
        VendorBillResponse bill = service.raiseBill(request, currentUser.getTenantId(), currentUser.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vendor bill raised", bill, 201));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_READ')")
    public ResponseEntity<PagedApiResponse<VendorBillResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<VendorBillResponse> result = service.list(currentUser.getTenantId(), pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Vendor bills retrieved", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_READ')")
    public ResponseEntity<ApiResponse<VendorBillResponse>> get(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Vendor bill retrieved", service.get(publicId, currentUser.getTenantId())));
    }

    @PostMapping("/{publicId}/payments")
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_MANAGE')")
    public ResponseEntity<ApiResponse<VendorBillResponse>> pay(
            @PathVariable UUID publicId,
            @Valid @RequestBody RecordVendorPaymentRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success("Payment recorded",
                service.recordPayment(publicId, request, currentUser.getTenantId(), currentUser.getEmail())));
    }

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_MANAGE')")
    public ResponseEntity<ApiResponse<VendorBillResponse>> cancel(
            @PathVariable UUID publicId,
            @Valid @RequestBody CancelVendorBillRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success("Vendor bill cancelled",
                service.cancelBill(publicId, request.getReason(), currentUser.getTenantId(), currentUser.getEmail())));
    }

    @GetMapping("/tds-summary")
    @PreAuthorize("hasAuthority('ACCOUNTING_TDS_READ')")
    public ResponseEntity<ApiResponse<List<TdsSummaryRow>>> tdsSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "TDS summary retrieved", service.tdsSummary(currentUser.getTenantId(), from, to)));
    }
}