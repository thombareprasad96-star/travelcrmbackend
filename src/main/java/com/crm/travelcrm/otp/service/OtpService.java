package com.crm.travelcrm.otp.service;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import com.crm.travelcrm.otp.OtpResult;

/**
 * Plug-and-play OTP facade — the only type a caller needs. Generation, hashing, storage, attempt
 * limiting, cooldown and channel delivery all sit behind it. Reuse it for any flow by adding an
 * {@link OtpPurpose}.
 */
public interface OtpService {

    /**
     * Generate, store (hashed) and deliver an OTP for {@code (purpose, destination)}. Silently
     * skips re-sending if a code was just issued (cooldown), so it's safe to call on every request.
     */
    void request(OtpPurpose purpose, String destination, OtpChannel channel);

    /** Verify a submitted code. One-time use — a SUCCESS consumes the code. */
    OtpResult verify(OtpPurpose purpose, String destination, String code);
}
