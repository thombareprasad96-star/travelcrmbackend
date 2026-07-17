package com.crm.travelcrm.leadsource.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * The raw log of one inbound delivery — what arrived, what we made of it, and why it failed.
 *
 * <p><b>Written FIRST, before verification, in its own committed transaction.</b> This deliberately
 * inverts the Razorpay precedent, which persists its ledger row LAST and therefore leaves no trace of
 * a failure — the exact reason dropped enquiries are invisible today. A rejected delivery must be
 * debuggable, so the row lands before the verdict.
 *
 * <p><b>Why this can be a {@link BaseTenantEntity} at all.</b> Owner decision 2: the tenant comes from
 * the URL token (or an HMAC-proven account key), never from the body. So by the time the body is
 * parsed <em>or fails to parse</em>, the tenant is already known:
 *
 * <table>
 *   <caption>Tenant knowability by failure mode</caption>
 *   <tr><th>Failure</th><th>Tenant known?</th><th>Action</th></tr>
 *   <tr><td>Token valid, body garbage</td><td><b>YES</b>, from the token</td>
 *       <td>Persist FAILED with the raw body — the tenant-scoped log works perfectly.</td></tr>
 *   <tr><td>Token unknown</td><td>NO</td>
 *       <td>An unauthenticated stranger. <b>No row, anywhere.</b> WARN + 401.</td></tr>
 * </table>
 *
 * <p><b>Unknown-token deliveries are DROPPED, never logged.</b> Persisting a stranger's body would be
 * an unauthenticated write primitive and a disk-fill DoS on a prefix that is unthrottled today. Note
 * the asymmetry that keeps this safe: a delivery to a KNOWN token on a DISABLED connection IS logged,
 * against the right tenant, so the tenant sees "you turned this off and are dropping leads" rather
 * than silence.
 *
 * <p>This is the opposite of {@code WaMessageLog}, which relies on ambient {@code TenantContext} and
 * would throw on a webhook thread. The ingest path never relies on ambient context — it stamps
 * {@link #setTenantId} explicitly from the resolved row.
 */
@Entity
@Table(
        name = "lead_ingest_events",
        indexes = {
                @Index(name = "idx_lie_tenant_created", columnList = "tenant_id,created_at"),
                @Index(name = "idx_lie_integration", columnList = "integration_id"),
                @Index(name = "idx_lie_status", columnList = "status"),
                @Index(name = "idx_lie_lead", columnList = "lead_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadIngestEvent extends BaseTenantEntity {

    /** Logical FK to {@code lead_source_integrations.id}. Nullable, no DB constraint. */
    @Column(name = "integration_id")
    private Long integrationId;

    /** {@code LeadSourceChannel.slug()}. Denormalised so the log survives the connection's deletion. */
    @Column(name = "channel", nullable = false, length = 40)
    private String channel;

    /**
     * The provider's own id for this event — the CONTENT idempotency key.
     *
     * <p>Partial-unique on {@code (tenant_id, channel, external_event_id)}, deliberately NOT the
     * platform-wide unique the {@code WebhookEvent} precedent uses. The reason is first-principles: an
     * external event id is provider-controlled and namespaced by an account we do not administer, so a
     * platform-wide unique would let any one connection permanently poison a key for every other
     * tenant — silent cross-tenant lead LOSS.
     */
    @Column(name = "external_event_id", length = 128)
    private String externalEventId;

    /** The transport-window dedup key from {@code adapter.dedupKey(in)}. Nullable. */
    @Column(name = "dedup_key", length = 128)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LeadIngestStatus status;

    /**
     * The delivery body, UTF-8, capped at 64KB, with the adapter's {@code secretFieldPaths()}
     * REDACTED first — otherwise a live credential sits in plaintext next to PII for the retention
     * window.
     */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /** True when {@link #rawPayload} was cut at the cap — so nobody debugs against a silent truncation. */
    @Column(name = "payload_truncated", nullable = false)
    @lombok.Builder.Default
    private boolean payloadTruncated = false;

    /**
     * Logical FK to {@code leads.id} — <b>no DB constraint, unlike {@code LeadLog}'s real FK</b>.
     *
     * <p>A LeadLog is a child that SHOULD die with its lead. An ingest event is an audit record that
     * must SURVIVE it: a real FK here would make the nightly trash purge throw on a 31-day-old
     * inbound lead and roll back every other tenant purge with it.
     */
    @Column(name = "lead_id")
    private Long leadId;

    /** Fetch/retry attempts made for a DEFERRED delivery. */
    @Column(name = "attempt_count", nullable = false)
    @lombok.Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // NO `received_at` column — use BaseEntity.createdAt.
    //
    // Two columns meaning "when did this arrive" drift the first time a retry path or a REQUIRES_NEW
    // boundary sets one and not the other, and support will not know which to trust. If a
    // provider-ASSERTED event time is ever wanted, it is a different column with a different name
    // (provider_event_at), documented as untrusted clock-skewed input and never used as a sort key.
}
