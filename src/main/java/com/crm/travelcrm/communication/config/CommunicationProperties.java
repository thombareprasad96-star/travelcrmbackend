package com.crm.travelcrm.communication.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tunables for the Communication Center. All prefixed {@code app.communication.*}.
 */
@Component
@ConfigurationProperties(prefix = "app.communication")
@Getter
@Setter
public class CommunicationProperties {

    /**
     * How long after a contact's last inbound message a free-text (non-template) reply is allowed.
     *
     * <p>24 hours is Meta's customer service window, not our choice — outside it the WhatsApp
     * Business API accepts approved templates only. Configurable because it is a provider policy
     * that has changed before, and because shortening it locally is the only way to test the
     * closed-window path without waiting a day.
     */
    private Duration whatsappSessionWindow = Duration.ofHours(24);

    /** Default page size for the inbox list. */
    private int defaultPageSize = 25;

    /** Hard cap on page size, so a hand-crafted {@code ?size=100000} cannot pull a tenant's inbox. */
    private int maxPageSize = 100;

    /** Characters of message body kept as the conversation-list preview. */
    private int previewLength = 200;

    /** How far back the hub's missed-call and follow-up tiles look. */
    private Duration summaryWindow = Duration.ofDays(30);
}
