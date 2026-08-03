package com.crm.travelcrm.notification.infrastructure;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fans a {@link NotifyEvent} out to the channels it names.
 *
 * <p><b>Save/restore on {@link TenantContext}, never set/clear.</b> {@code @EventListener} is
 * synchronous, so this runs on the publisher's own thread — usually a tenant request thread that has
 * plenty of work left to do. Clearing here would leave the rest of that request with a null tenant,
 * and {@code TenantFilterAspect} fails OPEN on null: every subsequent query silently spans all
 * tenants, with no error and no log. {@code ReminderScheduler} hand-rolls a re-set immediately after
 * publishing to work around exactly this; it is left as it is, since restoring here makes it
 * harmless rather than necessary.
 *
 * <p>{@code TenantScope} is the natural home for save/restore, but it deliberately refuses to be
 * entered inside an active transaction — and a publish very often is one — so the semantics are
 * repeated here instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventListener {

    private final List<NotificationChannel> channels;

    @EventListener
    public void onNotifyEvent(NotifyEvent event) {
        // The context is what satisfies TenantEntityListener.prePersist() in InAppNotificationChannel.
        Long previous = TenantContext.getTenantId();
        TenantContext.setTenantId(event.getTenantId());

        try {
            channels.stream()
                    .filter(ch -> event.getChannels().contains(ch.channelType()))
                    .forEach(ch -> {
                        try {
                            ch.send(event, null);
                        } catch (Exception e) {
                            log.error("Channel {} failed for event {}: {}",
                                    ch.channelType(), event.getType(), e.getMessage(), e);
                        }
                    });
        } finally {
            if (previous == null) {
                TenantContext.clear();          // nothing to restore, and no ThreadLocal left behind
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }
}