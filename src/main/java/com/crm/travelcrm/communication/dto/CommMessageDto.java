package com.crm.travelcrm.communication.dto;

import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.MessageDirection;
import com.crm.travelcrm.communication.enums.MessageStatus;
import com.crm.travelcrm.communication.enums.MessageVisibility;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row on the unified timeline.
 *
 * <p>Keyed by {@code publicId} only — the internal {@code Long id} is never exposed, and neither is
 * {@code senderUserId}: a staff member is identified to the client by name, and by their own user
 * publicId elsewhere. {@code providerMessageId} is deliberately absent too; it is an integration
 * detail with no meaning in the UI.
 *
 * <p>A message whose {@link #visibility} is PRIVATE only ever reaches a client that was entitled to
 * it — the exclusion happens in the repository query, not here.
 */
@Getter
@Builder
public class CommMessageDto {

    private final UUID publicId;
    private final UUID conversationPublicId;

    private final CommChannel channel;
    private final MessageDirection direction;
    private final MessageVisibility visibility;

    private final String bodyText;
    private final String bodyHtml;

    private final MessageStatus status;
    private final LocalDateTime statusAt;

    /** Populated only when {@link #status} is FAILED — what the provider said. */
    private final String errorMessage;

    /** Display name of the staff member who sent it. Null for every inbound message. */
    private final String senderName;

    /** When it actually happened (provider timestamp inbound, send time outbound). */
    private final LocalDateTime occurredAt;

    private final int attachmentCount;
    private final List<CommAttachmentDto> attachments;

    /** True for internal/private notes — the client renders these differently from real messages. */
    private final boolean note;

    /** Set when this row represents a logged call; the call detail is fetched separately. */
    private final UUID callPublicId;
}
