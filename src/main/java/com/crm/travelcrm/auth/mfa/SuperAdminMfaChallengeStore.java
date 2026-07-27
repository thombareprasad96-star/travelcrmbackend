package com.crm.travelcrm.auth.mfa;

import com.crm.travelcrm.common.entity.SuperAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived second-factor challenges after the password has been verified.
 * Single-node like the existing OTP store; move to Redis before running multiple app instances.
 */
@Service
public class SuperAdminMfaChallengeStore {

    private final ConcurrentHashMap<String, SuperAdminMfaChallenge> challenges =
            new ConcurrentHashMap<>();

    private final Duration ttl;
    private final int maxAttempts;

    public SuperAdminMfaChallengeStore(
            @Value("${app.mfa.challenge-ttl-seconds:300}") long ttlSeconds,
            @Value("${app.mfa.max-attempts:5}") int maxAttempts) {
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public CreatedChallenge create(SuperAdmin superAdmin, String clientIp, String userAgent) {
        String id = UUID.randomUUID().toString();
        SuperAdminMfaChallenge challenge = new SuperAdminMfaChallenge(
                superAdmin.getId(),
                superAdmin.getEmail(),
                superAdmin.getName(),
                superAdmin.getPublicId(),
                Instant.now().plus(ttl),
                clientIp,
                userAgent);
        challenges.put(id, challenge);
        return new CreatedChallenge(id, ttl.toSeconds());
    }

    public Optional<SuperAdminMfaChallenge> find(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        SuperAdminMfaChallenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            return Optional.empty();
        }
        if (challenge.isExpired(Instant.now())) {
            challenges.remove(challengeId);
            return Optional.empty();
        }
        return Optional.of(challenge);
    }

    public boolean recordFailure(String challengeId) {
        SuperAdminMfaChallenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            return false;
        }
        if (challenge.incrementAttempts() >= maxAttempts) {
            challenges.remove(challengeId);
            return false;
        }
        return true;
    }

    public void consume(String challengeId) {
        challenges.remove(challengeId);
    }

    @Scheduled(fixedRate = 60_000)
    public void evictExpired() {
        Instant now = Instant.now();
        challenges.values().removeIf(c -> c.isExpired(now));
    }

    public record CreatedChallenge(String id, long expiresInSeconds) {
    }
}
