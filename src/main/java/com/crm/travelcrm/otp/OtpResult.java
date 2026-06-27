package com.crm.travelcrm.otp;

/**
 * Outcome of {@link OtpService#verify}. The OTP module makes no assumption about how a caller wants
 * to surface failure (HTTP status, message, retry) — it returns this and the caller decides.
 */
public enum OtpResult {
    SUCCESS,
    INVALID,
    EXPIRED,
    TOO_MANY_ATTEMPTS,
    NOT_FOUND;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
