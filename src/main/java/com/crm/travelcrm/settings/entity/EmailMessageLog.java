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
 * Append-only log of email send attempts (quotation emails + test emails), used to compute the
 * "Sent Today" count and delivery rate on the settings hub. Tenant-scoped via {@link BaseTenantEntity}.
 */
@Entity
@Table(name = "email_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EmailMessageLog extends BaseTenantEntity {

    @Column(name = "recipient")
    private String recipient;

    @Column(name = "subject")
    private String subject;

    @Column(name = "status")
    private String status;                 // SENT | FAILED

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}