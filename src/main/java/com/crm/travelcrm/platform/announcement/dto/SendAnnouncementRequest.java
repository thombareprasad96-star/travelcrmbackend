package com.crm.travelcrm.platform.announcement.dto;

import com.crm.travelcrm.platform.announcement.enums.AnnouncementAudience;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementRecipientScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Compose + send a platform announcement. Audience/scope default (ALL / ADMINS) when omitted. */
@Data
public class SendAnnouncementRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String message;

    private AnnouncementAudience audience;

    private AnnouncementRecipientScope recipientScope;
}