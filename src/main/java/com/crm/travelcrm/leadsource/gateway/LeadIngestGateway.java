package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantScope;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import com.crm.travelcrm.leadsource.registry.LeadSourceRegistry;
import com.crm.travelcrm.leadsource.repository.LeadIngestEventRepository;
import com.crm.travelcrm.leadsource.spi.InboundParseResult;
import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The inbound pipeline: resolve → log → verify → parse → scope → delegate → publish.
 *
 * <p><b>NON-TRANSACTIONAL, and that is load-bearing rather than incidental.</b>
 * {@code TenantFilterAspect} is {@code @Before} on {@code @Transactional} and latches the tenant
 * filter before the method body runs, so the tenant must be set BEFORE any transaction opens. This
 * class sets it (via {@link TenantScope}) and then calls CROSS-BEAN into the transactional writers.
 * Making this class transactional, or inlining those writers into it, silently breaks both the filter
 * and {@code REQUIRES_NEW}.
 *
 * <h2>Why the entry point clears three contexts</h2>
 * These endpoints are {@code permitAll}, but the JWT filter still runs. An attacker who sends
 * {@code Authorization: Bearer <their own valid token>} would otherwise have
 * {@code JwtAuthFilter} populate {@code TenantContext} <b>from their token</b>, and this handler would
 * inherit a caller-chosen tenant it never asked for.
 * <ul>
 *   <li>{@code TenantContext} — the caller must not choose the tenant. The token does.</li>
 *   <li>{@code PlatformContext} — same, for the platform realm.</li>
 *   <li>{@code SecurityContextHolder} — {@code AuditorAware} reads it statically for {@code @CreatedBy},
 *       so a stale principal would stamp an attacker's email onto another tenant's row. A stale
 *       context would also arm the tenant filter and intermittently 401 a valid token — the exact
 *       symptom {@code ContextCleanupFilter} documents.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeadIngestGateway {

    private final LeadSourceRegistry registry;
    private final LeadIngestTokenResolver resolver;
    private final InboundVerifier verifier;
    private final PayloadRedactor redactor;
    private final LeadIngestDeliveryLogger deliveryLogger;
    private final LeadIngestService ingestService;
    private final LeadSourceCounterService counterService;
    private final LeadIngestEventRepository ingestEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * What gets persisted to {@code lead_ingest_events.raw_payload}.
     *
     * <p><b>A bodiless request would otherwise be logged as an empty string, destroying the only
     * evidence of what the provider sent.</b> That matters far more than it looks: a channel that pushes
     * leads as a GET query string ({@code ?name=…&mobile=…}) carries its ENTIRE payload outside the
     * body, so the delivery log — the thing that exists precisely so a rejected delivery is debuggable —
     * would record nothing at all. Every argument for shipping an adapter against an unverified contract
     * rests on the raw payload revealing the truth on first contact; for GET, that was not true.
     *
     * <p>Rendered as a JSON object rather than a raw query string on purpose: it keeps the stored column
     * one parseable shape, and it means {@link PayloadRedactor} — which can only redact within JSON —
     * still honours an adapter's {@code secretFieldPaths()} for a secret carried as a query parameter.
     * A raw {@code a=1&b=2} string would silently store such a secret in plaintext.
     *
     * <p>This does NOT touch {@link RawInbound#body()}, which must remain the exact received bytes: an
     * HMAC is computed over them.
     */
    private byte[] payloadToLog(byte[] body, Map<String, String> queryParams) {
        if (body != null && body.length > 0) {
            return body;
        }
        if (queryParams == null || queryParams.isEmpty()) {
            return body == null ? new byte[0] : body;
        }
        try {
            return objectMapper.writeValueAsBytes(queryParams);
        } catch (Exception e) {
            // Unreachable for a Map<String,String>, but a logging concern must never be able to reject
            // a real lead.
            log.warn("Could not render query parameters for the delivery log: {}", e.toString());
            return new byte[0];
        }
    }

    /**
     * Handle one delivery.
     *
     * @return what the controller should reply with
     */
    public IngestResponse handle(String channelSlug, String token, byte[] body,
                                 Map<String, String> headers, Map<String, String> queryParams) {

        // ── 0. CLEAR ── before anything else. See the class javadoc.
        TenantContext.clear();
        PlatformContext.clear();
        SecurityContextHolder.clearContext();

        // ── 2. FORMAT ── an unknown channel costs zero DB work.
        Optional<LeadSourceChannel> maybeChannel = LeadSourceChannel.fromSlug(channelSlug);
        if (maybeChannel.isEmpty()) {
            return IngestResponse.unauthorized();
        }
        LeadSourceChannel channel = maybeChannel.get();

        Optional<LeadSourceAdapter> maybeAdapter = registry.findAdapter(channel);
        if (maybeAdapter.isEmpty()) {
            // The slug is real but no adapter is deployed on this node. Identical response to an
            // unknown channel: the endpoint must never reveal what exists.
            return IngestResponse.unauthorized();
        }
        LeadSourceAdapter adapter = maybeAdapter.get();

        // ── 4. RESOLVE ── the only unscoped step, by necessity: the tenant is what we are resolving.
        Optional<ResolvedIntegration> maybeRow = resolver.resolveByToken(token);
        if (maybeRow.isEmpty()) {
            // An unauthenticated stranger. NO ROW IS PERSISTED, ANYWHERE: storing a stranger's body
            // would be an unauthenticated write primitive and a disk-fill DoS on a prefix that is
            // unthrottled today. 401 (not 404) so the provider stops rather than retrying forever.
            log.warn("Ingest delivery REJECTED: unknown or malformed token on channel '{}'.", channelSlug);
            return IngestResponse.unauthorized();
        }
        ResolvedIntegration row = maybeRow.get();

        // ── 5. VERIFY ──
        // The path segment is a cross-check, not decoration: it stops a token leaked from one
        // connection being replayed against another adapter's parser. 401 rather than 400 — a 400
        // would confirm the token is real but for a different channel.
        if (!channel.slug().equals(row.channel())) {
            log.warn("Ingest delivery REJECTED: token for channel '{}' presented at '{}' (integration {}).",
                    row.channel(), channelSlug, row.integrationId());
            return IngestResponse.unauthorized();
        }

        var raw = new HttpRawInbound(body, headers, queryParams, row.credentials(), objectMapper);

        // ── 7. LOG RAW ── committed BEFORE the verdict, so a rejected delivery is debuggable. This
        // deliberately inverts the Razorpay precedent, which logs last and therefore leaves no trace.
        //
        // Wrapped in TenantScope even though the INSERT stamps its tenant explicitly: the transaction
        // guard does not fire (no transaction is open here), REQUIRES_NEW still commits sequentially
        // before step 8 opens, and any read ever added to the logger is then tenant-filtered rather
        // than silently spanning every tenant.
        PayloadRedactor.Result payload =
                redactor.redact(payloadToLog(body, queryParams), adapter.secretFieldPaths());
        String dedupKey = safeDedupKey(adapter, raw);
        Long eventId = TenantScope.call(row.tenantId(), () ->
                deliveryLogger.logReceived(row.tenantId(), row.integrationId(), row.channel(),
                        null, dedupKey, payload.payload(), payload.truncated()));

        try {
            return process(channel, adapter, row, raw, eventId, dedupKey);
        } catch (Exception e) {
            log.error("Ingest delivery FAILED | tenant: {} | channel: {} | integration: {} | event: {}",
                    row.tenantId(), channel.slug(), row.integrationId(), eventId, e);
            finish(row, eventId, LeadIngestStatus.FAILED, null, e.toString());
            // 200 even on our own failure: the raw payload is stored, so the delivery is recoverable
            // by replay. A 5xx would make the provider retry a payload we have already recorded and,
            // after enough retries, disable the integration.
            return IngestResponse.accepted();
        } finally {
            // TenantScope restores on its own; ContextCleanupFilter is the backstop. This is belt and
            // braces on a pooled thread that will serve another tenant next.
            TenantContext.clear();
        }
    }

    private IngestResponse process(LeadSourceChannel channel, LeadSourceAdapter adapter,
                                   ResolvedIntegration row, HttpRawInbound raw, Long eventId,
                                   String dedupKey) {

        if (!verifier.verify(adapter.verification(), raw, channel.slug())) {
            finish(row, eventId, LeadIngestStatus.FAILED, null, "Signature verification failed");
            return IngestResponse.unauthorized();
        }

        // A DISABLED connection is logged against the RIGHT tenant and then rejected — the asymmetry
        // that makes the drop-strangers rule safe. The tenant can see "you turned this off and are
        // dropping leads" rather than nothing at all.
        if (!row.enabled() || "DISABLED".equals(row.status())) {
            log.warn("Ingest delivery to a DISABLED connection | tenant: {} | integration: {} ({})",
                    row.tenantId(), row.integrationId(), row.label());
            finish(row, eventId, LeadIngestStatus.IGNORED, null, "Connection is disabled");
            return IngestResponse.unauthorized();
        }

        InboundParseResult parsed;
        try {
            parsed = adapter.parse(raw);
        } catch (Exception e) {
            // A bad payload is never retried — it will be just as bad next time.
            log.warn("Ingest parse FAILED | channel: {} | integration: {} | {}",
                    channel.slug(), row.integrationId(), e.toString());
            finish(row, eventId, LeadIngestStatus.FAILED, null, "Parse failed: " + e);
            return IngestResponse.accepted();
        }

        return switch (parsed) {
            case InboundParseResult.Echo echo -> {
                finish(row, eventId, LeadIngestStatus.IGNORED, null, "Provider handshake");
                yield IngestResponse.echo(echo.body(), echo.contentType());
            }
            case InboundParseResult.Ignored ignored -> {
                log.info("Ingest delivery IGNORED ({}) | channel: {} | integration: {}",
                        ignored.reason(), channel.slug(), row.integrationId());
                finish(row, eventId, LeadIngestStatus.IGNORED, null, ignored.reason());
                touch(row);
                yield IngestResponse.accepted();
            }
            case InboundParseResult.Deferred deferred -> {
                // Phase 1 ships no fetcher. Failing loudly beats stranding the lead in a queue nothing
                // drains — the registry throws here, and the outer catch records FAILED.
                registry.fetcherFor(channel);
                finish(row, eventId, LeadIngestStatus.DEFERRED, null,
                        deferred.handles().size() + " handle(s) queued");
                yield IngestResponse.accepted();
            }
            case InboundParseResult.Complete complete ->
                    ingestAll(channel, row, eventId, dedupKey, complete.leads());
        };
    }

    private IngestResponse ingestAll(LeadSourceChannel channel, ResolvedIntegration row, Long eventId,
                                     String dedupKey, List<NormalizedLead> leads) {
        if (leads.isEmpty()) {
            finish(row, eventId, LeadIngestStatus.IGNORED, null, "Parsed to zero leads");
            return IngestResponse.accepted();
        }

        // Transport-window idempotency: a provider retrying a delivery we already handled.
        if (dedupKey != null && isDuplicate(row, dedupKey, eventId)) {
            log.info("Ingest delivery DUPLICATE (dedupKey) | channel: {} | integration: {}",
                    channel.slug(), row.integrationId());
            finish(row, eventId, LeadIngestStatus.DUPLICATE, null, "Duplicate delivery");
            return IngestResponse.accepted();
        }

        LeadIngestStatus worst = LeadIngestStatus.IGNORED;
        Long firstLeadId = null;
        String firstError = null;

        for (NormalizedLead normalized : leads) {
            LeadIngestOutcome outcome;
            try {
                // ── 8. SCOPE ── cross-bean into a method-level @Transactional writer, with the tenant
                // set BEFORE the transaction opens. Inlining this call would defeat both the proxy and
                // the filter.
                outcome = TenantScope.call(row.tenantId(), () ->
                        ingestService.ingest(row.tenantId(), channel, row.integrationId(), row.label(),
                                eventId, normalized));
            } catch (Exception e) {
                // PER-LEAD, not per-delivery. Meta sends several leads in one POST, and each one has
                // its own transaction; letting the first casualty out of this loop discarded every
                // lead BEHIND it in the batch — leads that had nothing wrong with them and that the
                // provider, holding a 200, would never send again. One bad lead costs one lead.
                log.error("Ingest FAILED for one lead of a {}-lead delivery; continuing with the rest "
                                + "| tenant: {} | channel: {} | integration: {} | event: {}",
                        leads.size(), row.tenantId(), channel.slug(), row.integrationId(), eventId, e);
                worst = moreSignificant(worst, LeadIngestStatus.FAILED);
                if (firstError == null) {
                    firstError = e.toString();
                }
                continue;
            }

            // ── 10. PUBLISH ── AFTER commit, from this non-transactional thread. Never inside the
            // transaction — see LeadIngestOutcome for the real (non-folklore) reason.
            publish(row, outcome);

            if (outcome.leadId() != null && firstLeadId == null) {
                firstLeadId = outcome.leadId();
            }
            worst = moreSignificant(worst, outcome.status());

            if (outcome.status() == LeadIngestStatus.PROCESSED
                    || outcome.status() == LeadIngestStatus.APPENDED) {
                recordReceived(row);
            }
        }

        finish(row, eventId, worst, firstLeadId, firstError);
        return IngestResponse.accepted();
    }

    /**
     * Which status a multi-lead delivery reports.
     *
     * <p>Meta batches several leads per POST, so one delivery can produce a mix. The event carries the
     * most CONSEQUENTIAL outcome rather than the last one, so a batch where one lead was created and
     * one was quota-blocked never reads as a clean success.
     */
    private LeadIngestStatus moreSignificant(LeadIngestStatus a, LeadIngestStatus b) {
        return rank(b) > rank(a) ? b : a;
    }

    /**
     * Every quarantine shares rank 4: they differ in WHY nothing was created, not in how consequential
     * that is. A batch that created one lead and quarantined another must not read as a clean success.
     */
    private int rank(LeadIngestStatus s) {
        return switch (s) {
            case IGNORED, DUPLICATE -> 0;
            case DEFERRED -> 1;
            case APPENDED -> 2;
            case PROCESSED -> 3;
            case QUARANTINED_QUOTA, QUARANTINED_DUPLICATE, QUARANTINED_TRASHED -> 4;
            case RECEIVED, FAILED -> 5;
        };
    }

    private boolean isDuplicate(ResolvedIntegration row, String dedupKey, Long currentEventId) {
        return TenantScope.call(row.tenantId(), () ->
                ingestEventRepository
                        .findFirstByTenantIdAndChannelAndDedupKeyAndDeletedAtIsNull(
                                row.tenantId(), row.channel(), dedupKey)
                        // The row we just wrote for THIS delivery carries the same key — it is not its
                        // own duplicate. (BaseEntity.id is a long primitive, not a Long.)
                        .filter(e -> e.getId() != currentEventId.longValue())
                        .isPresent());
    }

    private void publish(ResolvedIntegration row, LeadIngestOutcome outcome) {
        if (!outcome.shouldNotify()) {
            return;
        }
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(outcome.notifyType())
                .tenantId(row.tenantId())
                // No actorUserId: no human did this. Attributing it to one would be a lie in the audit.
                .recipientUserIds(outcome.recipientUserIds())
                .title(outcome.title())
                .message(outcome.message())
                .referenceType("LEAD")
                .referencePublicId(outcome.leadPublicId())
                // IN_APP only: IN_APP + SSE double-pushes, and EMAIL sleeps ~6s synchronously on this
                // thread — which is a webhook thread a provider is timing.
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    private void finish(ResolvedIntegration row, Long eventId, LeadIngestStatus status, Long leadId,
                        String error) {
        TenantScope.run(row.tenantId(), () ->
                deliveryLogger.finish(row.tenantId(), eventId, status, leadId, error));
    }

    private void recordReceived(ResolvedIntegration row) {
        TenantScope.run(row.tenantId(), () -> counterService.recordLeadReceived(row.integrationId()));
    }

    private void touch(ResolvedIntegration row) {
        TenantScope.run(row.tenantId(), () -> counterService.recordTokenUsed(row.integrationId()));
    }

    /**
     * An adapter's {@code dedupKey} is contractually pure, but a buggy one must not lose a real lead —
     * so a throw here degrades to "no dedup" rather than failing the delivery.
     */
    private String safeDedupKey(LeadSourceAdapter adapter, HttpRawInbound raw) {
        try {
            return adapter.dedupKey(raw).value();
        } catch (Exception e) {
            log.warn("{}.dedupKey threw ({}); treating this delivery as un-deduplicable.",
                    adapter.getClass().getSimpleName(), e.toString());
            return null;
        }
    }

    /**
     * What the controller replies.
     *
     * @param status      HTTP status
     * @param body        raw bytes for an {@link InboundParseResult.Echo} handshake; null otherwise
     * @param contentType only meaningful with {@code body}
     */
    public record IngestResponse(int status, byte[] body, String contentType) {

        /** The normal answer: we have durably recorded the delivery. Never a 4xx for our own problems. */
        static IngestResponse accepted() {
            return new IngestResponse(200, null, null);
        }

        /**
         * One response for every rejection — unknown channel, unknown token, wrong channel, bad
         * signature, disabled connection. Identical bodies on purpose: the endpoint must never let a
         * caller probe which tokens or channels exist.
         */
        static IngestResponse unauthorized() {
            return new IngestResponse(401, null, null);
        }

        static IngestResponse echo(byte[] body, String contentType) {
            return new IngestResponse(200, body, contentType);
        }
    }
}
