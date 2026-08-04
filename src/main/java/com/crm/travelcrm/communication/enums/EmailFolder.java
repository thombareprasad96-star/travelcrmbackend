package com.crm.travelcrm.communication.enums;

/**
 * Mailbox folder an email message belongs to — the Email screen's left rail.
 *
 * <p>OUR folder, not the server's. IMAP folder names are per-provider and per-language
 * ({@code [Gmail]/Sent Mail}, {@code Sent Items}, {@code Gesendet}), so the Phase 4 reader maps
 * whatever it finds onto these five and stores the mapping in the channel account. Persisting the
 * server's own name here would make the UI untranslatable and the query unindexable.
 */
public enum EmailFolder {

    INBOX,
    SENT,

    /** Composed but not sent. The only folder whose rows are editable. */
    DRAFTS,

    ARCHIVE,
    TRASH
}
