package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.communication.enums.ChannelAccountStatus;
import com.crm.travelcrm.communication.enums.CommChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One connected channel on the Channels &amp; Integrations screen.
 *
 * <h2>What this table does and does NOT store</h2>
 * WhatsApp and outbound SMTP credentials already live in {@code tenant_settings}, encrypted, and
 * are used by {@code WhatsAppMessagingService} and {@code TenantMailSenderFactory}. This table does
 * <b>not</b> duplicate them — for those two channels a row here (if present) carries only the label
 * and the last check result, and the status is DERIVED by reading tenant settings. Copying a secret
 * to render a status badge is how two copies of a credential drift apart.
 *
 * <p>IMAP (Phase 4), SMS (Phase 5) and Voice (Phase 6) have no home anywhere else, so those rows
 * DO carry {@link #secretEnc} — AES ciphertext through the same {@code AesSecretCipher} the settings
 * module uses. It is never returned to a client, at any level of privilege.
 *
 * <p>{@link #ingestToken} is the opaque per-tenant path segment for that channel's inbound webhook,
 * mirroring how {@code lead_source_integrations} resolves a tenant with no JWT. Stored HASHED, never
 * in the clear — the value is shown to the user exactly once, at creation.
 */
@Entity
@Table(name = "comm_channel_accounts", indexes = {
        @Index(name = "idx_cca_tenant",  columnList = "tenant_id"),
        @Index(name = "idx_cca_channel", columnList = "channel"),
        @Index(name = "idx_cca_status",  columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommChannelAccount extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommChannel channel;

    /** {@code INTERAKT}, {@code IMAP}, {@code TWILIO}, {@code EXOTEL}, … */
    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    /** What the user named it — "Sales WhatsApp", "info@agency.com". */
    @Column(name = "display_label", length = 150)
    private String displayLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ChannelAccountStatus status = ChannelAccountStatus.NOT_CONFIGURED;

    /** The account's own address: the WhatsApp number, the mailbox, the DID. */
    @Column(name = "external_ref", length = 190)
    private String externalRef;

    /** Non-secret provider settings as JSON — IMAP host/port, folder-name mapping, etc. */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /** AES ciphertext. Never returned to a client. Null for WhatsApp/SMTP — see the class javadoc. */
    @Column(name = "secret_enc", columnDefinition = "TEXT")
    private String secretEnc;

    /** SHA-256 of the inbound webhook token. The clear value is shown once, at creation. */
    @Column(name = "ingest_token_hash", length = 64)
    private String ingestToken;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Fetch cursor for a polling reader (IMAP). Opaque to everything but its own reader. */
    @Column(name = "sync_cursor", length = 190)
    private String syncCursor;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    public boolean isUsable() {
        return status == ChannelAccountStatus.CONNECTED;
    }
}
