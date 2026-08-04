package com.crm.travelcrm.communication.repository;

import java.util.UUID;

/**
 * Blob-free projection of {@code CommAttachment}.
 *
 * <p>The whole point is the absence of {@code content}: Spring Data builds a narrow SELECT from
 * these accessors, so a timeline page listing fifty attachments never pulls fifty files out of
 * Postgres. Adding a {@code getContent()} here would silently undo that.
 */
public interface CommAttachmentView {

    UUID getPublicId();

    Long getMessageId();

    String getFileName();

    String getContentType();

    long getSizeBytes();

    String getSha256();
}
