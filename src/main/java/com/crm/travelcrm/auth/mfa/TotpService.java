package com.crm.travelcrm.auth.mfa;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP for authenticator apps. Google Authenticator expects Base32 secrets,
 * HMAC-SHA1, 6 digits and a 30-second period unless the URI says otherwise.
 */
@Service
public class TotpService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int SECRET_BYTES = 20;       // 160-bit shared secret
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int VERIFICATION_WINDOW = 1; // previous/current/next time-step
    private static final char[] BASE32 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public boolean verify(String secretBase32, String code) {
        if (secretBase32 == null || code == null || !code.matches("^\\d{" + DIGITS + "}$")) {
            return false;
        }
        long counter = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        for (int offset = -VERIFICATION_WINDOW; offset <= VERIFICATION_WINDOW; offset++) {
            String expected = codeAt(secretBase32, counter + offset);
            if (MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    public String otpAuthUri(String issuer, String accountName, String secretBase32) {
        String safeIssuer = issuer == null || issuer.isBlank() ? "TravelCRM" : issuer.trim();
        String safeAccount = accountName == null || accountName.isBlank() ? "superadmin" : accountName.trim();
        String label = encodeUriComponent(safeIssuer + ":" + safeAccount);
        return "otpauth://totp/" + label
                + "?secret=" + secretBase32
                + "&issuer=" + encodeUriComponent(safeIssuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + PERIOD_SECONDS;
    }

    String codeAt(String secretBase32, long counter) {
        try {
            byte[] key = decodeBase32(secretBase32);
            byte[] msg = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(msg);

            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate TOTP code", ex);
        }
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1f;
                bitsLeft -= 5;
                out.append(BASE32[index]);
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return out.toString();
    }

    private static byte[] decodeBase32(String input) {
        String normalized = input.replace("=", "")
                .replace(" ", "")
                .trim()
                .toUpperCase(Locale.ROOT);

        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[normalized.length() * 5 / 8];
        int count = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = base32Value(normalized.charAt(i));
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        if (count == out.length) {
            return out;
        }
        byte[] exact = new byte[count];
        System.arraycopy(out, 0, exact, 0, count);
        return exact;
    }

    private static int base32Value(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return 26 + (c - '2');
        }
        throw new IllegalArgumentException("Invalid Base32 character");
    }

    private static String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
