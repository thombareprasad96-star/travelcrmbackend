package com.crm.travelcrm.communication.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Attachment metadata on a timeline row.
 *
 * <p>Carries no bytes and no URL. The file is reachable only through the ownership-checked download
 * endpoint keyed by {@link #publicId} — there is no public link to hand out, by design.
 */
@Getter
@Builder
public class CommAttachmentDto {

    private final UUID publicId;
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
}
