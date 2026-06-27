package com.crm.travelcrm.portal.payment;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.payment.dto.InitiatePaymentRequest;
import com.crm.travelcrm.portal.payment.dto.PaymentIntentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Traveler pay endpoint. Object-level ownership enforced in the service; delegates to the payment
 * hook. Body is optional — omit {@code amount} to pay the full pending balance.
 */
@RestController
@RequestMapping("/api/portal/payments")
@RequiredArgsConstructor
public class PortalPaymentController {

    private final PortalPaymentService portalPaymentService;

    @PostMapping("/bookings/{publicId}/intent")
    public ResponseEntity<ApiResponse<PaymentIntentResponse>> initiate(
            @PathVariable UUID publicId,
            @RequestBody(required = false) @Valid InitiatePaymentRequest request) {
        BigDecimal amount = request != null ? request.getAmount() : null;
        return ResponseEntity.ok(
                ApiResponse.success("Payment intent", portalPaymentService.initiate(publicId, amount)));
    }
}
