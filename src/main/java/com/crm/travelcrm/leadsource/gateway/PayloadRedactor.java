package com.crm.travelcrm.leadsource.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Strips an adapter's declared {@code secretFieldPaths()} out of a payload before it is persisted, and
 * caps its size.
 *
 * <p><b>Why this exists.</b> A channel that authenticates with a secret carried IN THE BODY would
 * otherwise have that live credential written to {@code lead_ingest_events.raw_payload} in plaintext,
 * next to PII, for the whole 30-day retention window — a real defect created purely by an SPI
 * omission. {@code LeadSourceAdapter.secretFieldPaths()} closes it, and this is what honours the
 * declaration.
 */
@Component
@RequiredArgsConstructor
public class PayloadRedactor {

    /** What a redacted value is replaced with — recognisable, and obviously not a real secret. */
    public static final String MASK = "***REDACTED***";

    /**
     * 64KB. Large enough for any real lead payload, small enough that a hostile or broken sender
     * cannot fill the disk one delivery at a time.
     */
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    private final ObjectMapper objectMapper;

    /** The payload as it should be stored. */
    public Result redact(byte[] body, Set<String> secretFieldPaths) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);

        if (!secretFieldPaths.isEmpty()) {
            text = redactJson(text, secretFieldPaths).orElse(text);
        }

        if (text.length() > MAX_PAYLOAD_CHARS) {
            return new Result(text.substring(0, MAX_PAYLOAD_CHARS), true);
        }
        return new Result(text, false);
    }

    /**
     * Redact within a JSON body.
     *
     * <p>Returns empty when the body is not JSON, and the caller then stores it unredacted. That is a
     * deliberate, narrow gap: the only channel shape that carries a secret in the body is JSON, and
     * blanket-regexing a non-JSON body for secret-looking substrings would mangle real lead content.
     * An adapter that ever declares a secret path on a form-encoded channel must extend this — hence
     * the explicit signal rather than a silent pass-through.
     */
    private java.util.Optional<String> redactJson(String text, Set<String> paths) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null || !root.isObject()) {
                return java.util.Optional.empty();
            }
            for (String path : paths) {
                maskPath((ObjectNode) root, path.split("\\."), 0);
            }
            return java.util.Optional.of(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private void maskPath(ObjectNode node, String[] segments, int depth) {
        if (depth == segments.length - 1) {
            if (node.has(segments[depth])) {
                node.put(segments[depth], MASK);
            }
            return;
        }
        JsonNode child = node.get(segments[depth]);
        if (child instanceof ObjectNode objectChild) {
            maskPath(objectChild, segments, depth + 1);
        }
    }

    /**
     * @param payload   what to store
     * @param truncated true when it was cut at the cap — recorded so nobody ever debugs against a
     *                  silent truncation and concludes the provider sent a malformed body
     */
    public record Result(String payload, boolean truncated) {}
}
