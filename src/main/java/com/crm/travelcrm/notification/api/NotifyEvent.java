package com.crm.travelcrm.notification.api;

import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only class a business module needs to import from the notification module.
 * Publish via {@code ApplicationEventPublisher.publishEvent(notifyEvent)}.
 *
 * <p>{@code type} is a free-form String constant defined by the calling module —
 * the notification module never needs to change when a new type is introduced.
 *
 * <p>Example:
 * <pre>{@code
 * applicationEventPublisher.publishEvent(
 *     NotifyEvent.builder()
 *         .type("LEAD_ASSIGNED")
 *         .tenantId(tenantId)
 *         .recipientUserIds(List.of(userId))
 *         .title("Lead assigned to you")
 *         .message("Lead ABC was assigned to you")
 *         .referenceType("LEAD").referencePublicId(lead.getPublicId())
 *         .channels(Set.of(DeliveryChannel.IN_APP, DeliveryChannel.EMAIL))
 *         .build());
 * }</pre>
 */
@Getter
@Builder
public class NotifyEvent {

    /** Free-form string — define constants in your own module. */
    private final String type;

    /** Must be non-null. The notification is tenant-scoped. */
    private final Long tenantId;

    /** Internal user IDs (Long, not publicId). One Notification row created per entry. */
    private final List<Long> recipientUserIds;

    /** Who triggered the event (optional). */
    private final Long actorUserId;

    private final String title;
    private final String message;

    /** The entity type that triggered this event, e.g. "LEAD", "BOOKING". */
    private final String referenceType;

    /** Public UUID of the source entity — safe to expose in responses. */
    private final UUID referencePublicId;

    /** Extra data passed to {@link TemplateRenderer} or stored for client rendering. */
    private final Map<String, Object> payload;

    /** Defaults to IN_APP only. */
    @Builder.Default
    private final Set<DeliveryChannel> channels = Set.of(DeliveryChannel.IN_APP);
}