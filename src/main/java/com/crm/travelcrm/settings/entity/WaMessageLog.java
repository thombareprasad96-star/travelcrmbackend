package com.crm.travelcrm.settings.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Append-only log of WhatsApp send attempts, used to compute the integration stats
 * (messages sent this month + delivery rate). Tenant-scoped via {@link BaseTenantEntity}.
 */
@Entity
@Table(name = "whatsapp_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class WaMessageLog extends BaseTenantEntity {

    @Column(name = "phone")
    private String phone;

    @Column(name = "template")
    private String template;

    @Column(name = "status")
    private String status;                 // SENT | FAILED

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}