package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.spi.InboundVerification;
import com.crm.travelcrm.leadsource.spi.RawInbound;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Runs an adapter's declared {@link InboundVerification} — once, correctly, for every channel.
 *
 * <p>Keeping the posture as DATA and the execution here is what avoids sixteen hand-rolled signature
 * comparisons, some of which would inevitably use {@code .equals()} and be timing-attackable. The
 * constant-time compare mirrors the {@code RazorpayGatewayClient} precedent
 * ({@code MessageDigest.isEqual}).
 *
 * <p><b>Everything fails CLOSED.</b> A missing secret, a missing header, a malformed signature — all
 * reject. In particular a payload arriving <b>without</b> a signature header is REJECTED, never
 * downgraded to {@code TokenOnly}: otherwise the strongest channel degrades to the weakest at the
 * attacker's option, which is the whole point of an attacker omitting the header.
 */
@Component
public class InboundVerifier {

    private static final Logger log = LogManager.getLogger(InboundVerifier.class);

    /** The credential-bag key an adapter's signing secret is stored under. */
    public static final String SIGNING_SECRET_KEY = "signingSecret";

    /** The credential-bag key a body-borne shared secret is compared against. */
    public static final String SHARED_SECRET_KEY = "sharedSecret";

    /**
     * @return true when the delivery is authentic (or the channel declares no signature at all)
     */
    public boolean verify(InboundVerification verification, RawInbound in, String channelSlug) {
        return switch (verification) {
            case InboundVerification.TokenOnly ignored -> true;   // the URL token IS the authentication
            case InboundVerification.HmacHeader h -> verifyHmac(h, in, channelSlug);
            case InboundVerification.SharedSecretInBody s -> verifySharedSecret(s, in, channelSlug);
        };
    }

    private boolean verifyHmac(InboundVerification.HmacHeader spec, RawInbound in, String channelSlug) {
        Optional<String> secret = in.credentials().get(SIGNING_SECRET_KEY);
        if (secret.isEmpty()) {
            log.warn("Rejecting {} delivery: the connection declares HMAC verification but has no "
                    + "'{}' credential. Failing closed.", channelSlug, SIGNING_SECRET_KEY);
            return false;
        }

        String presented = in.header(spec.header());
        if (presented == null || presented.isBlank()) {
            // NOT downgraded to "no signature required" — see the class javadoc.
            log.warn("Rejecting {} delivery: header '{}' is absent, but this channel requires an HMAC.",
                    channelSlug, spec.header());
            return false;
        }

        String expected;
        try {
            expected = spec.prefix() + encode(hmac(in.body(), secret.get(), spec.algo()), spec.enc());
        } catch (Exception e) {
            log.error("Could not compute the {} HMAC ({}): {}", channelSlug, spec.algo(),
                    e.getClass().getSimpleName());
            return false;
        }

        // The prefix is why this is not a copy of the Razorpay comparison: that one compares the
        // header verbatim against bare hex, which against a "sha256=<hex>" header fails 100% of the
        // time. Comparing the FULL expected string (prefix included) keeps it constant-time — stripping
        // the prefix first would branch on attacker-controlled input.
        return constantTimeEquals(expected, presented.trim());
    }

    private boolean verifySharedSecret(InboundVerification.SharedSecretInBody spec, RawInbound in,
                                       String channelSlug) {
        Optional<String> expected = in.credentials().get(SHARED_SECRET_KEY);
        if (expected.isEmpty()) {
            log.warn("Rejecting {} delivery: the connection declares a body shared secret but has no "
                    + "'{}' credential. Failing closed.", channelSlug, SHARED_SECRET_KEY);
            return false;
        }

        String presented = readPath(in, spec.jsonPath());
        if (presented == null) {
            log.warn("Rejecting {} delivery: no value at '{}' in the body.", channelSlug, spec.jsonPath());
            return false;
        }
        return constantTimeEquals(expected.get(), presented.trim());
    }

    /**
     * Reads a dotted path out of the body — JSON first, then form-encoded.
     *
     * <p>Deliberately minimal (no arrays, no wildcards): this reads ONE secret field, and a general
     * path language here would be an invitation to put parsing logic in the wrong layer.
     */
    private String readPath(RawInbound in, String path) {
        JsonNode node = in.json();
        if (node != null) {
            for (String segment : path.split("\\.")) {
                node = node.get(segment);
                if (node == null) break;
            }
            if (node != null && node.isValueNode()) {
                return node.asText();
            }
        }
        return in.form().get(path);
    }

    private static byte[] hmac(byte[] data, String secret, String algo) throws Exception {
        Mac mac = Mac.getInstance(algo);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algo));
        return mac.doFinal(data);
    }

    private static String encode(byte[] raw, InboundVerification.Enc enc) {
        return switch (enc) {
            case HEX -> HexFormat.of().formatHex(raw);
            case BASE64 -> Base64.getEncoder().encodeToString(raw);
        };
    }

    /** Constant-time, so verification cannot be timing-attacked. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
