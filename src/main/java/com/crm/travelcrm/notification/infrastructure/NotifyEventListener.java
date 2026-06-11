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

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventListener {

    private final List<NotificationChannel> channels;

    @EventListener
    public void onNotifyEvent(NotifyEvent event) {
        // TenantContext set karo — InAppNotificationChannel ke prePersist ke liye zaroori
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
            TenantContext.clear(); // ThreadLocal leak prevent karo
        }
    }
}