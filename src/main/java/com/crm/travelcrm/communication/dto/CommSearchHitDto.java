package com.crm.travelcrm.communication.dto;

import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.MessageDirection;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One hit from the global search — a matching message, with just enough thread context to render a
 * result row and navigate to it.
 *
 * <p>Deliberately flat rather than a nested message + conversation: a search page shows a hundred
 * rows and only ever needs the contact, the channel, when, and a snippet.
 */
@Getter
@Builder
public class CommSearchHitDto {

    private final UUID messagePublicId;
    private final UUID conversationPublicId;

    private final CommChannel channel;
    private final MessageDirection direction;

    private final String contactName;
    private final String contactValue;

    /** Trimmed body around the match. Plain text — never the HTML body. */
    private final String snippet;

    private final LocalDateTime occurredAt;
}
