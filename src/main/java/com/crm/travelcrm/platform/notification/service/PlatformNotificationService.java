package com.crm.travelcrm.platform.notification.service;

import com.crm.travelcrm.platform.notification.dto.PlatformNotificationResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** Console feed for the platform SuperAdmin. Every method scopes to the authenticated SuperAdmin. */
public interface PlatformNotificationService {

    Page<PlatformNotificationResponseDTO> getNotifications(int page, int size);

    long getUnreadCount();

    PlatformNotificationResponseDTO markRead(UUID publicId);

    void markAllRead();

    void delete(UUID publicId);

    SseEmitter subscribe();
}