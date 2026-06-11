package com.crm.travelcrm.notification.web.dto;

import com.crm.travelcrm.notification.domain.entity.Reminder;
import com.crm.travelcrm.notification.domain.enums.ReminderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class ReminderResponseDTO {

    private UUID publicId;
    private String title;
    private String notes;
    private Instant remindAt;
    private ReminderStatus status;
    private Instant snoozedUntil;
    private String referenceType;
    private UUID referencePublicId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ReminderResponseDTO from(Reminder r) {
        // Mask PROCESSING as PENDING — it's an internal scheduler state
        ReminderStatus publicStatus = r.getStatus() == ReminderStatus.PROCESSING
                ? ReminderStatus.PENDING
                : r.getStatus();

        return ReminderResponseDTO.builder()
                .publicId(r.getPublicId())
                .title(r.getTitle())
                .notes(r.getNotes())
                .remindAt(r.getRemindAt())
                .status(publicStatus)
                .snoozedUntil(r.getSnoozedUntil())
                .referenceType(r.getReferenceType())
                .referencePublicId(r.getReferencePublicId())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}