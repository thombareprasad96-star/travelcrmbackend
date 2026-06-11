package com.crm.travelcrm.notification.api;

import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;

/**
 * Contract for every delivery channel (IN_APP, EMAIL, SSE, …).
 *
 * <p>Adding a new channel requires only:
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Annotate with {@code @Component}.</li>
 *   <li>Add the new value to {@link DeliveryChannel}.</li>
 * </ol>
 * The {@code NotificationDispatcher} never changes (O principle).
 *
 * <p>Each implementation is substitutable (L principle): a failure in one channel
 * must not propagate to others — the dispatcher wraps each call in try/catch.
 */
public interface NotificationChannel {

    /** Identifies which {@link DeliveryChannel} this implementation handles. */
    DeliveryChannel channelType();

    /**
     * Deliver the notification.
     *
     * @param event        the original event (title, message, payload, etc.)
     * @param notification the persisted {@link Notification} created by the in-app channel
     *                     (may be {@code null} for channels that fire before persistence)
     */
    void send(NotifyEvent event, Notification notification);
}