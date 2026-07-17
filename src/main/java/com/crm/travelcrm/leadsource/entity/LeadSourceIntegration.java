package com.crm.travelcrm.leadsource.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One tenant's CONNECTION to a lead channel.
 *
 * <p><b>MANY rows per (tenant, channel) — the row is a connection, not a config.</b> Two JustDial city
 * accounts are two credential bags, two ingest tokens, two enable switches. The existing
 * {@code TenantSettings} shape (one flat row per tenant, 13 columns for 2 integrations, no type
 * discriminator, no connection status) is the proof of what forcing one-row-per-channel costs: it
 * pushes connections into a child table later, which is the same mistake at a different grain.
 * <b>Uniqueness is on the TOKEN, never on {@code (tenant, channel)}.</b>
 *
 * <p><b>Credentials are hybrid, split by one mechanical rule:</b>
 * <blockquote>Typed column ⟺ the FRAMEWORK queries it. Encrypted blob ⟺ only the OWNING ADAPTER reads
 * it, after the tenant is resolved.</blockquote>
 * Channel credential shapes genuinely differ (Meta: page token + expiry + refresh + page id; JustDial:
 * one key; WEBSITE_FORM: no credential but an allowed-origins list), so "one adapter class and nothing
 * else" and "a column per credential" are directly contradictory. {@link #credentialsExpireAt} stays
 * TYPED anyway — a refresh sweep must ask "which connections expire within 7 days" across tenants
 * <em>without decrypting every row</em>, and that single query is what makes this a hybrid rather than
 * a pure blob.
 *
 * <p><b>Two blobs, not one — this is what makes the write-only-secret contract STRUCTURAL.</b>
 * {@link #configJson} is frontend-returnable wholesale; {@link #credentialsEnc} never is. One combined
 * blob would force every GET to decrypt and hand-filter — a blacklist, when the rule here is
 * whitelist-never-blacklist. Two columns makes the whitelist a property of the schema rather than of
 * someone remembering.
 *
 * <p><b>No JSON/JSONB type is introduced, and that is forced rather than conceded:</b> the bag is
 * encrypted, so the column holds opaque Base64 and Postgres could not index inside it regardless.
 * Encryption forces TEXT.
 *
 * <p><b>{@code AesSecretCipher} reuse hazard.</b> It is a plain component with explicit
 * {@code encrypt()}/{@code decrypt()} — there is no {@code AttributeConverter} anywhere in this
 * codebase, so {@link #credentialsEnc} silently stores PLAINTEXT if any write path forgets the call.
 * Encrypt at exactly ONE chokepoint (the config service), never per call site.
 */
@Entity
@Table(
        name = "lead_source_integrations",
        indexes = {
                // The tenant resolver's lookup. Not unique here — uniqueness is enforced by the
                // soft-delete-aware partial unique indexes in db/indexes.sql.
                @Index(name = "idx_lsi_token_hash", columnList = "ingest_token_hash"),
                @Index(name = "idx_lsi_token_hash_prev", columnList = "ingest_token_hash_previous"),
                @Index(name = "idx_lsi_tenant_channel", columnList = "tenant_id,channel"),
                @Index(name = "idx_lsi_external_account", columnList = "external_account_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadSourceIntegration extends BaseTenantEntity {

    /**
     * {@code LeadSourceChannel.slug()} — a plain VARCHAR, deliberately NOT {@code @Enumerated} and
     * with no CHECK constraint.
     *
     * <p>The registry is the constraint, and a stricter one: it rejects an unknown slug at save time
     * AND at ingest, whereas a CHECK only validates spelling and would accept a {@code meta_ads} row
     * on a node with no Meta adapter deployed. It also keeps "add a channel" to "write one adapter"
     * rather than "write one adapter and remember the constraint refresh".
     */
    @Column(name = "channel", nullable = false, length = 40)
    private String channel;

    /** The tenant's own name for this connection: "Mumbai DID", "Goa Packages page". */
    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "enabled", nullable = false)
    @lombok.Builder.Default
    private boolean enabled = true;

    /** {@code TenantResolution.name()} — TOKEN | PROVIDER_ACCOUNT. */
    @Column(name = "resolution_mode", nullable = false, length = 24)
    private String resolutionMode;

    /**
     * SHA-256 hex of the ingest token. <b>THE tenant resolver.</b>
     *
     * <p>Hashed, not encrypted: {@code AesSecretCipher} uses a random 12-byte IV per {@code encrypt()},
     * so its ciphertext is non-deterministic and <b>cannot back an indexed lookup</b>. That kills AES
     * for this column on technical grounds, not preference. SHA-256 needs no salt here — the token is
     * 256 bits of CSPRNG output, so there is no dictionary to attack.
     *
     * <p>Nullable: {@code PROVIDER_ACCOUNT} connections have no per-connection URL and therefore no
     * token.
     */
    @Column(name = "ingest_token_hash", length = 64)
    private String ingestTokenHash;

    /**
     * The previous token's hash, honoured until {@link #tokenPreviousRevokeAt}.
     *
     * <p>Rotation must OVERLAP. A tenant pastes the new URL into JustDial's console minutes or days
     * after rotating it here; without an overlap window, every lead in between is lost — silently,
     * because the provider gets a 404 and simply stops.
     */
    @Column(name = "ingest_token_hash_previous", length = 64)
    private String ingestTokenHashPrevious;

    /**
     * The token's first few characters, in PLAINTEXT. For masked display and log correlation ONLY —
     * never for authentication.
     */
    @Column(name = "ingest_token_prefix", length = 24)
    private String ingestTokenPrefix;

    @Column(name = "token_rotated_at")
    private LocalDateTime tokenRotatedAt;

    /** When the previous token stops being accepted. Null ⇒ no overlap window is open. */
    @Column(name = "token_previous_revoke_at")
    private LocalDateTime tokenPreviousRevokeAt;

    /** Powers "this connection has never received anything" in the UI. Written on ingest. */
    @Column(name = "token_last_used_at")
    private LocalDateTime tokenLastUsedAt;

    /** FB page id / IVR DID / site key. The {@code PROVIDER_ACCOUNT} lookup key. Nullable. */
    @Column(name = "external_account_id", length = 128)
    private String externalAccountId;

    /** Base64(AES-GCM(json)). Adapter-only, after tenant resolution. <b>NEVER returned to a client.</b> */
    @Column(name = "credentials_enc", columnDefinition = "TEXT")
    private String credentialsEnc;

    /** Plaintext JSON. Non-secret settings. Frontend-returnable wholesale. */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * Typed on purpose — see the class javadoc. The cross-tenant "expiring soon" sweep must not have
     * to decrypt every row to ask.
     */
    @Column(name = "credentials_expire_at")
    private LocalDateTime credentialsExpireAt;

    /** Which encryption key version {@link #credentialsEnc} was written with. */
    @Column(name = "key_version", nullable = false)
    @lombok.Builder.Default
    private short keyVersion = 1;

    /** CONNECTED | DEGRADED | DISABLED. */
    @Column(name = "status", nullable = false, length = 24)
    @lombok.Builder.Default
    private String status = "CONNECTED";

    /**
     * Leads received through this connection.
     *
     * <p>Updated by an atomic {@code @Modifying} JPQL increment — never read-modify-write, and never
     * {@code @Version}-guarded: <b>an optimistic-lock conflict on a stats counter must never be able to
     * reject a real lead.</b>
     */
    @Column(name = "lead_count", nullable = false)
    @lombok.Builder.Default
    private long leadCount = 0L;

    @Column(name = "last_lead_received_at")
    private LocalDateTime lastLeadReceivedAt;
}
