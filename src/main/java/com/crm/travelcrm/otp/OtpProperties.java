package com.crm.travelcrm.otp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code app.otp.*}. One place for the whole module's tunables — no magic numbers
 * scattered across generator/store/service.
 */
@Component
@ConfigurationProperties(prefix = "app.otp")
public class OtpProperties {

    /** Number of digits in a generated code. */
    private int length = 6;

    /** How long a code stays valid. */
    private long ttlSeconds = 300;

    /** Wrong-guess cap before the code is locked (must re-request). */
    private int maxAttempts = 5;

    /** Minimum gap between two sends to the same destination/purpose (anti-spam). */
    private long resendCooldownSeconds = 60;

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getResendCooldownSeconds() { return resendCooldownSeconds; }
    public void setResendCooldownSeconds(long resendCooldownSeconds) {
        this.resendCooldownSeconds = resendCooldownSeconds;
    }
}
