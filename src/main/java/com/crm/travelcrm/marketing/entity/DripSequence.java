package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.marketing.enums.DripAudienceType;
import com.crm.travelcrm.marketing.enums.DripStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A multi-step automated journey. Steps live in {@code marketing_drip_steps} keyed by
 * {@code sequenceId}. When ACTIVE and segment-driven, matching customers are auto-enrolled.
 */
@Entity
@Table(
        name = "marketing_drip_sequences",
        indexes = {
                @Index(name = "idx_mkt_drip_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_mkt_drip_status",  columnList = "tenant_id,status"),
                @Index(name = "idx_mkt_drip_owner",   columnList = "tenant_id,owner_user_id"),
                @Index(name = "idx_mkt_drip_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class DripSequence extends BaseTenantEntity implements Ownable {

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DripStatus status = DripStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 20)
    private DripAudienceType audienceType;

    @Column(name = "segment_id")
    private Long segmentId;

    @Column(name = "segment_public_id")
    private java.util.UUID segmentPublicId;

    @Column(name = "segment_name_snapshot", length = 150)
    private String segmentNameSnapshot;
}