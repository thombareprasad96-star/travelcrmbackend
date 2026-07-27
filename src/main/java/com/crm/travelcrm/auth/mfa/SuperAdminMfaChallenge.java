package com.crm.travelcrm.auth.mfa;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class SuperAdminMfaChallenge {

    private final Long superAdminId;
    private final String email;
    private final String name;
    private final UUID publicId;
    private final Instant expiresAt;
    private final String clientIp;
    private final String userAgent;
    private final AtomicInteger attempts = new AtomicInteger();

    SuperAdminMfaChallenge(Long superAdminId, String email, String name, UUID publicId,
                           Instant expiresAt, String clientIp, String userAgent) {
        this.superAdminId = superAdminId;
        this.email = email;
        this.name = name;
        this.publicId = publicId;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    public Long superAdminId() { return superAdminId; }
    public String email() { return email; }
    public String name() { return name; }
    public UUID publicId() { return publicId; }
    public Instant expiresAt() { return expiresAt; }
    public String clientIp() { return clientIp; }
    public String userAgent() { return userAgent; }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    int incrementAttempts() {
        return attempts.incrementAndGet();
    }
}
