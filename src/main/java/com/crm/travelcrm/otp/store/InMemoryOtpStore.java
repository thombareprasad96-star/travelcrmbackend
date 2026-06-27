package com.crm.travelcrm.otp.store;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default OTP store — in-memory, zero infrastructure, ideal for single-node and dev. Self-cleans
 * expired entries on access. To run multi-node, add a Redis/DB-backed {@link OtpStore} bean marked
 * {@code @Primary} and this one steps aside (it is just an unused bean) — no other code changes.
 */
@Component
public class InMemoryOtpStore implements OtpStore {

    private final ConcurrentHashMap<String, OtpRecord> store = new ConcurrentHashMap<>();

    @Override
    public void save(String key, OtpRecord record) {
        store.put(key, record);
    }

    @Override
    public Optional<OtpRecord> find(String key) {
        OtpRecord record = store.get(key);
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(Instant.now())) {
            store.remove(key);                    // lazy eviction
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public void incrementAttempts(String key) {
        OtpRecord record = store.get(key);
        if (record != null) {
            record.incrementAttempts();
        }
    }
}
