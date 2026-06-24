package com.crm.travelcrm.quotation.analytics;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated weblink-view log: one upserted row per (tenant, quotation, IP). Each public view
 * increments {@code viewCount} and bumps {@code lastViewedAt}.
 *
 * <p>Standalone entity (NOT {@code BaseTenantEntity}): the public write path has no
 * {@code TenantContext}, so {@code tenantId} is set explicitly from the resolved quotation and
 * the {@code @PrePersist} tenant listener is intentionally not involved. Log table — no soft-delete.
 */
@Entity
@Table(
        name = "quotation_weblink_view",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_qwv_tenant_quotation_ip",
                columnNames = {"tenant_id", "quotation_public_id", "ip_address"}),
        indexes = {
                @Index(name = "idx_qwv_quotation", columnList = "quotation_public_id"),
                @Index(name = "idx_qwv_tenant", columnList = "tenant_id")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuotationWeblinkView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(name = "public_id", updatable = false, nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "quotation_id", nullable = false)
    private Long quotationId;

    @Column(name = "quotation_public_id", nullable = false)
    private UUID quotationPublicId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ip_address", nullable = false, length = 64)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "viewer_type", nullable = false, length = 20)
    private ViewerType viewerType;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "first_viewed_at", nullable = false)
    private Instant firstViewedAt;

    @Column(name = "last_viewed_at", nullable = false)
    private Instant lastViewedAt;

    @Column(name = "user_agent", length = 400)
    private String userAgent;
}