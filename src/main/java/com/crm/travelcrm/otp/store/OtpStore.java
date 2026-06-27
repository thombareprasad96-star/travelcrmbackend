package com.crm.travelcrm.otp.store;

import java.util.Optional;

/**
 * Storage SPI for in-flight OTPs (DIP). The default {@link InMemoryOtpStore} needs no infra; provide
 * a {@code @Primary} Redis/DB-backed bean to swap it for a clustered deployment — nothing else in the
 * module changes.
 */
public interface OtpStore {

    void save(String key, OtpRecord record);

    Optional<OtpRecord> find(String key);

    void delete(String key);

    /** Atomically bump the wrong-guess counter for a stored code (no-op if absent). */
    void incrementAttempts(String key);
}
