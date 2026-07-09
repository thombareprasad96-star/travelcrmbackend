package com.crm.travelcrm.platform.announcement.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementAudience;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementRecipientScope;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A platform broadcast sent by a SuperAdmin to tenants (delivered as in-app notifications via the
 * notification module). Platform-level — extends {@link BaseEntity}, never tenant-scoped. Append-only
 * history: {@code createdAt} is the send time, and the tenant/recipient counts are a snapshot of the
 * fan-out at send time.
 */
@Entity
@Table(name = "announcements",
        indexes = @Index(name = "idx_announcement_created", columnList = "created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement extends BaseEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT", nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    private AnnouncementAudience audience;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_scope", nullable = false, length = 20)
    private AnnouncementRecipientScope recipientScope;

    @Column(name = "tenant_count", nullable = false)
    private int tenantCount;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "sent_by_email", length = 150)
    private String sentByEmail;
}