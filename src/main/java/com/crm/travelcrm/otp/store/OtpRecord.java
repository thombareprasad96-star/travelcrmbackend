package com.crm.travelcrm.otp.store;

import java.time.Instant;

/**
 * A stored, in-flight OTP — the <b>hash</b> of the code (never the plaintext), its validity window
 * and the running wrong-guess count. Storage-agnostic value object shared by every {@link OtpStore}.
 */
public class OtpRecord {

    private final String hashedCode;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private int attempts;

    public OtpRecord(String hashedCode, Instant issuedAt, Instant expiresAt) {
        this.hashedCode = hashedCode;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.attempts = 0;
    }

    public String getHashedCode() { return hashedCode; }
    public Instant getIssuedAt()  { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts()      { return attempts; }

    public void incrementAttempts() { this.attempts++; }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
}
