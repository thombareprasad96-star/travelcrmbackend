package com.crm.travelcrm.otp.service;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpKeyBuilder;
import com.crm.travelcrm.otp.OtpProperties;
import com.crm.travelcrm.otp.OtpPurpose;
import com.crm.travelcrm.otp.OtpResult;
import com.crm.travelcrm.otp.delivery.OtpSenderResolver;
import com.crm.travelcrm.otp.generator.OtpGenerator;
import com.crm.travelcrm.otp.store.OtpRecord;
import com.crm.travelcrm.otp.store.OtpStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates the collaborators (generator, store, sender, hasher) — each of which is independently
 * replaceable. The code is hashed before storage (only a hash ever rests in the store), verification
 * is attempt-capped and one-time-use, and resends are cooldown-guarded.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final OtpGenerator generator;
    private final OtpStore store;
    private final OtpSenderResolver senderResolver;
    private final PasswordEncoder passwordEncoder;
    private final OtpProperties properties;

    @Override
    public void request(OtpPurpose purpose, String destination, OtpChannel channel) {
        String key = OtpKeyBuilder.build(purpose, destination);
        Instant now = Instant.now();

        // Anti-spam: don't re-issue if the last code is still within the resend cooldown.
        OtpRecord existing = store.find(key).orElse(null);
        if (existing != null
                && existing.getIssuedAt().plusSeconds(properties.getResendCooldownSeconds()).isAfter(now)) {
            log.info("[OTP] resend within cooldown for {} — skipping", key);
            return;
        }

        String code = generator.generate(properties.getLength());
        OtpRecord record = new OtpRecord(
                passwordEncoder.encode(code), now, now.plusSeconds(properties.getTtlSeconds()));
        store.save(key, record);
        senderResolver.resolve(channel, destination).deliver(destination, code, purpose);
    }

    @Override
    public OtpResult verify(OtpPurpose purpose, String destination, String code) {
        String key = OtpKeyBuilder.build(purpose, destination);
        OtpRecord record = store.find(key).orElse(null);

        if (record == null) {
            return OtpResult.NOT_FOUND;          // never issued, or already expired/consumed
        }
        if (record.isExpired(Instant.now())) {
            store.delete(key);
            return OtpResult.EXPIRED;
        }
        if (record.getAttempts() >= properties.getMaxAttempts()) {
            store.delete(key);
            return OtpResult.TOO_MANY_ATTEMPTS;
        }
        if (!passwordEncoder.matches(code, record.getHashedCode())) {
            store.incrementAttempts(key);
            return OtpResult.INVALID;
        }
        store.delete(key);                       // one-time use
        return OtpResult.SUCCESS;
    }
}
