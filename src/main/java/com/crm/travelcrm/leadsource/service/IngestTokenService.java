package com.crm.travelcrm.leadsource.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints and hashes the opaque per-connection ingest token — the thing that identifies a tenant on a
 * public endpoint.
 *
 * <pre>
 * FORMAT   lsk_ + Base64URL-unpadded(32 bytes SecureRandom)
 * STORAGE  SHA-256 hex, 64 chars  →  lead_source_integrations.ingest_token_hash
 * </pre>
 *
 * <h2>Why SHA-256 and not BCrypt — a deliberate divergence from the OTP module</h2>
 * <ul>
 *   <li><b>BCrypt's per-row salt is unindexable.</b> Resolution is {@code WHERE ingest_token_hash =
 *       sha256(presented)} — an O(1) unique btree probe. With BCrypt the only way to find the row
 *       would be to scan every connection and BCrypt-compare each one.</li>
 *   <li><b>~100ms of KDF on an endpoint any unauthenticated caller can flood with garbage is a
 *       CPU-exhaustion amplifier masquerading as rigour.</b></li>
 *   <li>The OTP module needs BCrypt because a 6-digit code is ~20 bits and genuinely brute-forceable.
 *       <b>This is not that.</b></li>
 * </ul>
 *
 * <h2>Why unsalted is safe HERE and only here</h2>
 * The input is 256 bits of {@link SecureRandom}. There is no dictionary and nothing to brute-force, so
 * a salt would defend against an attack that cannot exist. Never reuse this reasoning for anything a
 * human chooses.
 *
 * <h2>Why not a split selector+verifier token</h2>
 * That design was argued from a false premise — "a hashed token cannot be looked up by index" is true
 * of BCrypt (per-row salt) and of AES-GCM (random IV per encrypt, which is exactly why AES cannot back
 * this column), but <b>not</b> of SHA-256, which is deterministic. The split bought nothing and cost a
 * permanent plaintext partial-credential at rest.
 *
 * <h2>The {@code lsk_} prefix</h2>
 * Kept SOLELY so secret scanners can grep for it, and so a malformed token is rejected before any
 * database access.
 */
@Service
public class IngestTokenService {

    /** Greppable by secret scanners. Not a security control in itself. */
    public static final String TOKEN_PREFIX = "lsk_";

    /** 256 bits — the reason no salt is needed. */
    private static final int TOKEN_BYTES = 32;

    /** Stored in plaintext for masked display and log correlation. Never for authentication. */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    /**
     * A fresh token. <b>Returned exactly once</b> — only its hash is stored, so a tenant who loses it
     * must rotate, not recover.
     */
    public String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the whole token — what lands in {@code ingest_token_hash}. */
    public String hash(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Cannot hash a null ingest token");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS on every conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Cheap shape check, run BEFORE any database access so garbage costs a string compare rather than
     * a query. Says nothing about whether the token is real.
     */
    public boolean looksWellFormed(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        String body = token.substring(TOKEN_PREFIX.length());
        // 32 bytes Base64URL-unpadded is exactly 43 chars.
        if (body.length() != 43) {
            return false;
        }
        return body.chars().allMatch(c ->
                (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_');
    }

    /** The plaintext fragment shown in the UI and used to correlate logs. Never authenticates anything. */
    public String displayPrefix(String token) {
        if (token == null) return null;
        return token.length() <= DISPLAY_PREFIX_LENGTH ? token : token.substring(0, DISPLAY_PREFIX_LENGTH);
    }
}
