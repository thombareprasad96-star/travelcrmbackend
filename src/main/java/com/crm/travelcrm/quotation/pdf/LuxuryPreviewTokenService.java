package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.quotation.pdf.config.LuxuryPdfProperties;
import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and redeems the one-shot tokens Chromium uses to fetch a quotation's Luxury HTML.
 *
 * <p><b>Why a token instead of just letting the browser call the normal endpoint.</b> Chromium is a
 * separate process: it does not have the caller's JWT and cannot be given one without putting a
 * staff credential into a URL. The internal preview route therefore has to be reachable without the
 * staff filter chain — which would be an unauthenticated endpoint accepting arbitrary quotation
 * UUIDs, i.e. a way to read any tenant's quotation by guessing an id.
 *
 * <p>The token closes that hole by <b>carrying the rendered document itself</b>, not a reference to
 * one. Authorisation happens once, in the authenticated service call that mints the token: the
 * quotation is loaded tenant-scoped, mapped, and the finished view model is stashed here. The
 * internal route then serves what it is handed and never performs a lookup, so there is no id for
 * an attacker to substitute — a forged token resolves to nothing at all.
 *
 * <p>Tokens are single-use and short-lived ({@code pdf.luxury.preview-token-ttl-seconds}). In-memory
 * by design: the token's whole life is the few hundred milliseconds between minting it and Chromium
 * fetching it on the same host, so surviving a restart or reaching a second node would buy nothing
 * and would mean writing customer data to a shared store.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LuxuryPreviewTokenService {

    /** 32 bytes of {@link SecureRandom} — not a UUID, which is only 122 bits and often time-derived. */
    private static final int TOKEN_BYTES = 32;

    private final LuxuryPdfProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    private record Entry(LuxuryQuotationPdfDto payload, Instant expiresAt) {}

    /** Stashes a rendered view model and returns the opaque token that redeems it exactly once. */
    public String mint(LuxuryQuotationPdfDto payload) {
        purgeExpired();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, new Entry(payload,
                Instant.now().plusSeconds(properties.getPreviewTokenTtlSeconds())));
        return token;
    }

    /**
     * Redeems a token, removing it. Returns null for unknown, already-used and expired tokens
     * alike — the caller answers 404 for all three, so nothing about which case it was leaks.
     *
     * <p>The token value is never logged. It is a bearer credential for one customer's quotation for
     * as long as it lives, and log files outlive it.
     */
    public LuxuryQuotationPdfDto redeem(String token) {
        if (token == null || token.isBlank()) return null;
        Entry entry = tokens.remove(token);
        if (entry == null) return null;
        if (entry.expiresAt().isBefore(Instant.now())) {
            log.debug("Luxury preview token expired before it was used");
            return null;
        }
        return entry.payload();
    }

    /**
     * Drops timed-out entries on the way in.
     *
     * <p>Swept on mint rather than by a scheduled job: entries only appear when someone mints one,
     * so a background task would spend its life waking up to find an empty map. Without any sweep a
     * render that fails after minting — Chromium never navigates — would leak its view model for the
     * lifetime of the process.
     */
    private void purgeExpired() {
        if (tokens.isEmpty()) return;
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
