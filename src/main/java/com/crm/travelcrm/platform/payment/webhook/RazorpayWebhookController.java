package com.crm.travelcrm.platform.payment.webhook;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.payment.service.SaasPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound Razorpay webhooks. Permitted without a JWT (server-to-server; there is no user) — the
 * authenticity gate is the HMAC-SHA256 signature verified in {@link SaasPaymentService}, NOT the
 * filter chain. The body is taken as raw {@code byte[]} because the signature is computed over the
 * exact bytes received; re-serializing a parsed object would change them and break verification.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private final SaasPaymentService saasPaymentService;

    @PostMapping(value = "/razorpay", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<ApiResponse<Void>> razorpay(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        saasPaymentService.handleRazorpayWebhook(rawBody, signature, eventId);
        return ResponseEntity.ok(ApiResponse.success("ok"));
    }
}
