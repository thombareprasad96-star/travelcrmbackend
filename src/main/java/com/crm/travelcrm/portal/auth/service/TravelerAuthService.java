package com.crm.travelcrm.portal.auth.service;

import com.crm.travelcrm.portal.auth.dto.PortalLoginResponse;

/** Traveler login: OTP request + verify. Identity (and tenant) is resolved from the identifier. */
public interface TravelerAuthService {

    /**
     * Generate + deliver an OTP for the customer behind this identifier. Silent no-op (no error,
     * no information leak) when the identifier matches no active customer, so the endpoint cannot
     * be used to enumerate customers.
     */
    void requestOtp(String identifier);

    /** Verify the OTP and, on success, issue a short-lived traveler token. 401 on any failure. */
    PortalLoginResponse verifyOtp(String identifier, String otp);
}
