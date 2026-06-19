package com.crm.travelcrm.notification.web.dto;

import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.NotificationStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class NotificationResponseDTO {

    private Long id;
    private UUID publicId;
    private String type;
    private String title;
    private String message;
    private NotificationStatus status;
    private String referenceType;
    private UUID referencePublicId;
    private Instant readAt;
    private LocalDateTime createdAt;

    public static NotificationResponseDTO from(Notification n) {
        return NotificationResponseDTO.builder()
                .id(n.getId())
                .publicId(n.getPublicId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .status(n.getStatus())
                .referenceType(n.getReferenceType())
                .referencePublicId(n.getReferencePublicId())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }

    /** Ephemeral SSE-only push that has no DB row yet. */
    public static NotificationResponseDTO ephemeral(NotifyEvent event) {
        return NotificationResponseDTO.builder()
                .type(event.getType())
                .title(event.getTitle())
                .message(event.getMessage())
                .status(NotificationStatus.UNREAD)
                .referenceType(event.getReferenceType())
                .referencePublicId(event.getReferencePublicId())
                .createdAt(LocalDateTime.now())
                .build();
    }
}