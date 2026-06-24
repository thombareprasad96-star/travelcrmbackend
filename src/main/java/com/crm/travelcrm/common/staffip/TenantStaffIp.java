package com.crm.travelcrm.common.staffip;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One IP that a tenant's staff have logged in from — the tenant's "home IP" set. Used to
 * classify public quotation weblink views as HOME (staff) vs EXTERNAL (client). Captured
 * best-effort at login. Standalone (not tenant-filtered): {@code tenantId} is an explicit column.
 */
@Entity
@Table(
        name = "tenant_staff_ip",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_staff_ip_tenant_ip", columnNames = {"tenant_id", "ip_address"}),
        indexes = @Index(name = "idx_staff_ip_tenant", columnList = "tenant_id")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantStaffIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}