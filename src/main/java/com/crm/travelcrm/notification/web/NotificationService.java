package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Plug-and-play notification service — consumed only by NotificationController.
 * All queries are scoped to the currently authenticated user (resolved internally
 * from SecurityContext). No caller ever passes a userId or tenantId.
 */
public interface NotificationService {

    /** Paginated notification feed for the current user. */
    Page<NotificationResponseDTO> getNotifications(int page, int size);

    /** Unread badge count for the current user. */
    long getUnreadCount();

    /** Mark a single notification (by numeric id) as read. Throws 404 if not owned by current user. */
    NotificationResponseDTO markRead(Long id);

    /** Bulk mark-all-read for the current user. */
    void markAllRead();

    /** Soft-delete a single notification (by numeric id). Throws 404 if not owned by current user. */
    void delete(Long id);

    /**
     * Open an SSE stream for the current user.
     * Used by the SSE endpoint — caller must NOT close the emitter; the registry handles it.
     */
    SseEmitter subscribe();
}