package com.crm.travelcrm.accounting.report.controller;

import com.crm.travelcrm.accounting.report.dto.AccountingDashboardResponse;
import com.crm.travelcrm.accounting.report.dto.GstSummaryResponse;
import com.crm.travelcrm.accounting.report.dto.PnlReportResponse;
import com.crm.travelcrm.accounting.report.service.AccountingDashboardService;
import com.crm.travelcrm.accounting.report.service.AccountingReportService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Accounting reports — a tax-aware P&L and a GST summary. Gated on {@code ACCOUNTING_INVOICE_READ}
 * (which the ACCOUNTANT holds) rather than the CRM report package's {@code CRM_FULL}, so the finance
 * team can see them.
 */
@RestController
@RequestMapping("/api/accounting/reports")
@RequiredArgsConstructor
public class AccountingReportController {

    private final AccountingReportService service;
    private final AccountingDashboardService dashboardService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<AccountingDashboardResponse>> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounts dashboard", dashboardService.dashboard(currentUser.getTenantId(), from, to)));
    }

    @GetMapping("/pnl")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<PnlReportResponse>> pnl(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "P&L report", service.pnl(currentUser.getTenantId(), from, to)));
    }

    @GetMapping("/gst-summary")
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<GstSummaryResponse>> gstSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "GST summary", service.gstSummary(currentUser.getTenantId(), from, to)));
    }
}