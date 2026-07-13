package com.crm.travelcrm.platform.payment.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.payment.dto.PaymentOrderResponse;
import com.crm.travelcrm.platform.payment.service.SaasPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-facing SaaS billing payment. The tenant admin creates a gateway order for one of their own
 * UNPAID platform invoices, then completes it in the browser Razorpay Checkout; the
 * {@code payment.captured} webhook settles the invoice. The invoice's tenant is derived from the
 * authenticated principal — never a request parameter.
 */
@RestController
@RequestMapping("/api/company/billing")
@RequiredArgsConstructor
public class TenantBillingPaymentController {

    private final SaasPaymentService saasPaymentService;

    @PostMapping("/{invoicePublicId}/pay-intent")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<PaymentOrderResponse>> payIntent(
            @PathVariable UUID invoicePublicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Payment order created",
                saasPaymentService.createOrderForInvoice(invoicePublicId, currentUser.getTenantId())));
    }
}
