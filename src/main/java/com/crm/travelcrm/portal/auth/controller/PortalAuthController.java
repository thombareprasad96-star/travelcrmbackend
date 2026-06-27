package com.crm.travelcrm.portal.auth.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.auth.dto.OtpRequestDto;
import com.crm.travelcrm.portal.auth.dto.OtpVerifyDto;
import com.crm.travelcrm.portal.auth.dto.PortalLoginResponse;
import com.crm.travelcrm.portal.auth.service.TravelerAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) traveler login — the only open portal endpoints. Rate-limited per IP by
 * {@code RateLimitFilter}; brute-force-capped per account by the OTP attempt counter.
 */
@Slf4j
@RestController
@RequestMapping("/api/portal/auth")
@RequiredArgsConstructor
public class PortalAuthController {

    private final TravelerAuthService travelerAuthService;

    @PostMapping("/request-otp")
    public ResponseEntity<ApiResponse<Void>> requestOtp(@Valid @RequestBody OtpRequestDto dto) {
        travelerAuthService.requestOtp(dto.getIdentifier());
        // Always the same response — never reveals whether the identifier matched a customer.
        return ResponseEntity.ok(ApiResponse.success(
                "If an account exists for that contact, a one-time code has been sent."));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<PortalLoginResponse>> verifyOtp(@Valid @RequestBody OtpVerifyDto dto) {
        PortalLoginResponse response = travelerAuthService.verifyOtp(dto.getIdentifier(), dto.getOtp());
        return ResponseEntity.ok(ApiResponse.success("Logged in", response));
    }
}
