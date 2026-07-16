package com.crm.travelcrm.platform.billing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.dto.CreateBillingRequest;
import com.crm.travelcrm.platform.billing.dto.SeatFeeResponse;
import com.crm.travelcrm.platform.billing.dto.SubAgentPricingResponse;
import com.crm.travelcrm.platform.billing.dto.UpdateSeatFeeRequest;
import com.crm.travelcrm.platform.billing.dto.UpdateSubAgentPricingRequest;
import com.crm.travelcrm.platform.billing.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Platform billing. Guarded by {@code ROLE_SUPER_ADMIN}; tenants keyed by {@code publicId}. */
@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @GetMapping("/tenants/{tenantPublicId}/billing")
    public ResponseEntity<ApiResponse<List<BillingRecordResponse>>> list(@PathVariable UUID tenantPublicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Billing history fetched", billingService.listForTenant(tenantPublicId)));
    }

    @PostMapping("/tenants/{tenantPublicId}/billing")
    public ResponseEntity<ApiResponse<BillingRecordResponse>> create(
            @PathVariable UUID tenantPublicId, @Valid @RequestBody CreateBillingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                "Invoice issued", billingService.create(tenantPublicId, request)));
    }

    @GetMapping("/tenants/{tenantPublicId}/seat-fee")
    public ResponseEntity<ApiResponse<SeatFeeResponse>> getSeatFee(@PathVariable UUID tenantPublicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Seat fee fetched", billingService.getSeatFee(tenantPublicId)));
    }

    @PutMapping("/tenants/{tenantPublicId}/seat-fee")
    public ResponseEntity<ApiResponse<SeatFeeResponse>> setSeatFee(
            @PathVariable UUID tenantPublicId, @Valid @RequestBody UpdateSeatFeeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Seat fee updated", billingService.setSeatFee(tenantPublicId, request)));
    }

    // ── Platform-wide Travel Partner seat pricing (same across all tenants) ──
    @GetMapping("/subagent-pricing")
    public ResponseEntity<ApiResponse<SubAgentPricingResponse>> getSubAgentPricing() {
        return ResponseEntity.ok(ApiResponse.success(
                "Travel Partner seat pricing fetched", billingService.getSubAgentPricing()));
    }

    @PutMapping("/subagent-pricing")
    public ResponseEntity<ApiResponse<SubAgentPricingResponse>> setSubAgentPricing(
            @Valid @RequestBody UpdateSubAgentPricingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Travel Partner seat pricing updated", billingService.setSubAgentPricing(request)));
    }

    @PostMapping("/billing/{publicId}/mark-paid")
    public ResponseEntity<ApiResponse<BillingRecordResponse>> markPaid(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Invoice marked paid", billingService.markPaid(publicId)));
    }

    @PostMapping("/billing/{publicId}/mark-unpaid")
    public ResponseEntity<ApiResponse<BillingRecordResponse>> markUnpaid(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Invoice marked unpaid", billingService.markUnpaid(publicId)));
    }
}