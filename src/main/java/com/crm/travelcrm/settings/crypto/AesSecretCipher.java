package com.crm.travelcrm.settings.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256/GCM encryption for tenant integration secrets (SMTP password, WhatsApp API key).
 * The key comes from {@code app.encryption.key} (Base64, 16/24/32 bytes) — env-provided in prod,
 * never committed. Output is Base64 of {@code iv || ciphertext+tag}. GCM gives authenticated
 * encryption so tampered ciphertext fails to decrypt.
 */
@Component
public class AesSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // 96-bit nonce (GCM recommended)
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesSecretCipher(@Value("${app.encryption.key}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            throw new IllegalStateException("app.encryption.key is not set");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "app.encryption.key must decode to 16, 24 or 32 bytes (got " + raw.length + ")");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Encrypts UTF-8 plaintext; returns Base64(iv || ciphertext). Null in → null out. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /** Reverses {@link #encrypt}. Null in → null out. */
    public String decrypt(String base64Cipher) {
        if (base64Cipher == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(base64Cipher);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }
}