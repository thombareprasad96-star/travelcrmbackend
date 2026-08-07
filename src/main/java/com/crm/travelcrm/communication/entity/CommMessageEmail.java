package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.communication.enums.EmailFolder;
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

/**
 * The email-only half of a {@link CommMessage} — RFC 5322 headers and mailbox placement.
 *
 * <p>A side table, not columns on {@code comm_messages}: eight header fields (three of them
 * unbounded address lists) on the hottest table in the application, NULL for every WhatsApp, SMS,
 * call and note row, would be pure width on every timeline read. This is the same shape
 * {@code Booking} uses for its non-audited children.
 *
 * <p><b>{@link #messageIdHeader} / {@link #inReplyTo} / {@link #references} are what make threading
 * work</b>, and they are the mail system's identifiers, not ours. Phase 4's reader threads by
 * walking {@code References} newest-to-oldest and falling back to {@code In-Reply-To}; subject
 * matching is deliberately NOT used, because "Re: Dubai Package" from two different customers is one
 * thread under that rule.
 *
 * <p>{@link #folder} is OUR vocabulary. IMAP folder names are per-provider and per-language
 * ({@code [Gmail]/Sent Mail}, {@code Sent Items}, {@code Gesendet}); the reader maps whatever the
 * server reports onto {@link EmailFolder} and keeps the mapping on the channel account, so the UI
 * stays translatable and the column stays indexable.
 */
@Entity
@Table(name = "comm_message_emails", indexes = {
        @Index(name = "idx_cme_tenant",     columnList = "tenant_id"),
        @Index(name = "idx_cme_message",    columnList = "message_id"),
        @Index(name = "idx_cme_folder",     columnList = "folder"),
        @Index(name = "idx_cme_header_id",  columnList = "message_id_header"),
        @Index(name = "idx_cme_in_reply_to", columnList = "in_reply_to")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommMessageEmail extends BaseTenantEntity {

    /** {@code comm_messages.id}. One row per email message, never shared. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "from_address", length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 150)
    private String fromName;

    /** Comma-separated address list. TEXT because a distribution list has no sane upper bound. */
    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    /**
     * BCC. Populated ONLY on messages we sent — a received email never discloses its own BCC list,
     * and inventing one from the envelope recipient would leak the other recipients.
     */
    @Column(name = "bcc_addresses", columnDefinition = "TEXT")
    private String bccAddresses;

    // ── Threading (RFC 5322). See the class javadoc. ───────────────────────────────────────────

    /** This message's own {@code Message-ID} header, angle brackets included. */
    @Column(name = "message_id_header", length = 255)
    private String messageIdHeader;

    @Column(name = "in_reply_to", length = 255)
    private String inReplyTo;

    /** The full {@code References} chain, space-separated, oldest first. */
    @Column(name = "references_header", columnDefinition = "TEXT")
    private String references;

    // ── Mailbox placement ─────────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "folder", nullable = false, length = 10)
    @Builder.Default
    private EmailFolder folder = EmailFolder.INBOX;

    @Column(name = "starred", nullable = false)
    @Builder.Default
    private boolean starred = false;

    /** True while this is an unsent draft — the only state in which the body is editable. */
    @Column(name = "is_draft", nullable = false)
    @Builder.Default
    private boolean draft = false;

    /**
     * The server-side UID the reader last saw this at.
     *
     * <p>IMAP UIDs are per-mailbox and reset when {@code UIDVALIDITY} changes, so this is a fetch
     * cursor aid, never an identity. Identity is {@link #messageIdHeader}.
     */
    @Column(name = "imap_uid")
    private Long imapUid;
}
