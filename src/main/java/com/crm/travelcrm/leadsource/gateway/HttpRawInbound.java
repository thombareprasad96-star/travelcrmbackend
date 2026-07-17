package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.spi.IntegrationCredentials;
import com.crm.travelcrm.leadsource.spi.RawInbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link RawInbound} over one servlet request's captured bytes.
 *
 * <p>The typed accessors are lazy and cached, and every one of them reads the <b>same immutable
 * bytes</b> — {@link #body()} is never re-derived from a parsed form, because an HMAC is computed over
 * the exact received bytes and re-serializing changes them.
 *
 * <p>Not thread-safe, and does not need to be: one instance serves one delivery on one thread. The
 * ADAPTERS are the singletons that must hold no state.
 */
public final class HttpRawInbound implements RawInbound {

    private final byte[] body;
    private final Map<String, String> headers;       // lower-cased keys
    private final Map<String, String> queryParams;
    private final IntegrationCredentials credentials;
    private final ObjectMapper objectMapper;

    private boolean jsonParsed;
    private JsonNode jsonCache;
    private Map<String, String> formCache;

    public HttpRawInbound(byte[] body,
                          Map<String, String> headers,
                          Map<String, String> queryParams,
                          IntegrationCredentials credentials,
                          ObjectMapper objectMapper) {
        this.body = body == null ? new byte[0] : body;
        this.headers = lowerCaseKeys(headers);
        this.queryParams = queryParams == null ? Map.of() : Map.copyOf(queryParams);
        this.credentials = credentials == null ? IntegrationCredentials.empty() : credentials;
        this.objectMapper = objectMapper;
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> in) {
        if (in == null) return Map.of();
        Map<String, String> out = new HashMap<>();
        in.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return Map.copyOf(out);
    }

    /**
     * The exact received bytes.
     *
     * <p>Returned by reference rather than cloned: this is on the hot path of a public endpoint and a
     * body can be tens of kilobytes. Adapters are contractually pure and must not mutate it — the
     * ArchUnit rules enforce the surrounding discipline, and a defensive copy per delivery is a real
     * cost for a contract violation that would be caught in review.
     */
    @Override
    public byte[] body() {
        return body;
    }

    @Override
    public JsonNode json() {
        if (!jsonParsed) {
            jsonParsed = true;   // set first: a malformed body must not be re-parsed on every call
            try {
                jsonCache = body.length == 0 ? null : objectMapper.readTree(body);
            } catch (Exception e) {
                // Not JSON. A legitimate case — several channels post form-encoded — so this is not an
                // error, and parse() decides what to make of it.
                jsonCache = null;
            }
        }
        return jsonCache;
    }

    @Override
    public Map<String, String> form() {
        if (formCache == null) {
            formCache = parseForm(new String(body, StandardCharsets.UTF_8));
        }
        return formCache;
    }

    private static Map<String, String> parseForm(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                        URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // A malformed %-escape. Skip the pair rather than failing the whole delivery.
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public String header(String name) {
        return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String query(String name) {
        return queryParams.get(name);
    }

    /** Already immutable — copied once in the constructor. */
    @Override
    public Map<String, String> queryAll() {
        return queryParams;
    }

    @Override
    public IntegrationCredentials credentials() {
        return credentials;
    }
}
