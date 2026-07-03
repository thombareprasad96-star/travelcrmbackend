package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetDocumentType;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One fired document-expiry alert. Doubles as the scan's idempotency marker: the same
 * (ref, docType, expiryDate, threshold) tuple never fires twice, and a renewed document
 * (new expiry date) naturally starts a fresh alert series. Rows are system-generated
 * history — never user-edited, trashed or restored (hence not in {@code TrashableType}).
 */
@Entity
@Table(name = "fleet_document_alerts", indexes = {
        @Index(name = "idx_fleet_alert_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_alert_ref", columnList = "ref_type, ref_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetDocumentAlert extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 10)
    private FleetRefType refType;

    /** Internal id of the vehicle/driver — logical reference, same-module so purge-safe. */
    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Column(name = "ref_public_id", nullable = false)
    private UUID refPublicId;

    /** Display snapshot at alert time — vehicle number or driver name. */
    @Column(name = "ref_label", length = 150)
    private String refLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private FleetDocumentType docType;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    /** Days until expiry when the alert fired (negative = already expired). */
    @Column(name = "days_left", nullable = false)
    private long daysLeft;

    /** The configured threshold this alert satisfied (30/15/7/0 …). */
    @Column(name = "threshold_days", nullable = false)
    private int thresholdDays;
}
