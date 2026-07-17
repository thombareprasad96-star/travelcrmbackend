package com.crm.travelcrm.platform.notification.dto;

import com.crm.travelcrm.platform.notification.entity.PlatformNotification;
import com.crm.travelcrm.platform.notification.enums.PlatformNotificationStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Console-facing view of a platform notification.
 *
 * <p>No internal {@code Long} id and no internal {@code tenantId} — the console identifies a tenant
 * by {@code tenantPublicId} and shows {@code tenantName}, per BaseEntity's rule that internal ids
 * never leave the server.
 */
@Getter
@Builder
public class PlatformNotificationResponseDTO {

    private UUID publicId;
    private String type;
    private String title;
    private String message;
    private PlatformNotificationStatus status;
    private String referenceType;
    private UUID referencePublicId;

    /** Which tenant this is about — display context. Null for platform-wide events. */
    private String tenantName;
    private UUID tenantPublicId;

    private Instant readAt;
    private LocalDateTime createdAt;

    public static PlatformNotificationResponseDTO from(PlatformNotification n) {
        return PlatformNotificationResponseDTO.builder()
                .publicId(n.getPublicId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .status(n.getStatus())
                .referenceType(n.getReferenceType())
                .referencePublicId(n.getReferencePublicId())
                .tenantName(n.getTenantName())
                .tenantPublicId(n.getTenantPublicId())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}