package com.crm.travelcrm.notificationsetting.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

// Org-wide automatic-reminder configuration — one row per tenant. These are
// organization policies (how reminders are auto-created as a lead changes stage),
// so they are tenant-scoped, not per-user. The per-stage list is stored as JSON.
@Entity
@Table(
    name = "notification_settings",
    uniqueConstraints = @UniqueConstraint(name = "uq_notification_settings_tenant", columnNames = "tenant_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class NotificationSetting extends BaseTenantEntity {

    // JSON array of stage configs: [{ key, enabled, reminderType, hours, priority,
    // titleTemplate, descTemplate }, ...]
    @Column(name = "settings_json", columnDefinition = "TEXT")
    private String settingsJson;
}