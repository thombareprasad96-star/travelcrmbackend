package com.crm.travelcrm.leadsource.web;

import com.crm.travelcrm.leadsource.gateway.LeadIngestGateway;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The public inbound-lead endpoint. One URL shape for every channel.
 *
 * <pre>
 * POST /api/webhooks/leads/{channel}/{token}
 * GET  /api/webhooks/leads/{channel}/{token}   — provider handshakes only
 * </pre>
 *
 * <p><b>A deliberately thin controller</b>: it captures the request and hands it to
 * {@link LeadIngestGateway}. It must stay that way — the gateway is non-transactional on purpose, and
 * any logic that drifts up here runs before the contexts are cleared.
 *
 * <p><b>{@code @RequestBody byte[]} with {@code consumes = ALL_VALUE}</b>, matching the sole existing
 * webhook precedent. Both halves matter: an HMAC is computed over the EXACT received bytes, so letting
 * Spring parse and re-serialize the body would break every signature; and {@code ALL_VALUE} is
 * required because providers send JSON, form-encoded and occasionally a wrong or absent content type.
 *
 * <p><b>This endpoint does not use the {@code ApiResponse} envelope</b>, and that is the one documented
 * exception in this codebase. The caller is a machine that only reads the status code, and a provider
 * handshake ({@code Echo}) requires replying with exact bytes the provider chose.
 */
@RestController
@RequestMapping("/api/webhooks/leads")
@RequiredArgsConstructor
public class LeadIngestWebhookController {

    private final LeadIngestGateway gateway;

    @PostMapping(value = "/{channel}/{token}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> receive(@PathVariable String channel,
                                          @PathVariable String token,
                                          @RequestBody(required = false) byte[] body,
                                          HttpServletRequest request) {
        return toResponse(gateway.handle(channel, token, body, headers(request), queryParams(request)));
    }

    /**
     * Provider handshakes (Meta's {@code hub.challenge}).
     *
     * <p>Needs its own mapping AND its own security permit: the existing webhook rule is
     * POST-qualified, so a GET would otherwise fall through to {@code anyRequest().authenticated()}
     * and 401 the handshake.
     */
    @GetMapping("/{channel}/{token}")
    public ResponseEntity<byte[]> handshake(@PathVariable String channel,
                                            @PathVariable String token,
                                            HttpServletRequest request) {
        return toResponse(gateway.handle(channel, token, new byte[0], headers(request),
                queryParams(request)));
    }

    private ResponseEntity<byte[]> toResponse(LeadIngestGateway.IngestResponse result) {
        if (result.body() == null) {
            return ResponseEntity.status(result.status()).build();
        }
        return ResponseEntity.status(result.status())
                .header("Content-Type", result.contentType())
                .body(result.body());
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name, request.getHeader(name));
        }
        return out;
    }

    /** First value only — a signature or a challenge is never legitimately repeated. */
    private Map<String, String> queryParams(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) out.put(k, v[0]);
        });
        return out;
    }
}
