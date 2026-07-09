package com.crm.travelcrm.platform.announcement.dto;

import com.crm.travelcrm.platform.announcement.enums.AnnouncementAudience;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementRecipientScope;

import java.time.LocalDateTime;
import java.util.UUID;

/** A sent announcement for the console history. */
public record AnnouncementResponse(
        UUID publicId,
        String title,
        String message,
        AnnouncementAudience audience,
        AnnouncementRecipientScope recipientScope,
        int tenantCount,
        int recipientCount,
        String sentByEmail,
        LocalDateTime createdAt) {
}