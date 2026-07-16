package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.marketing.enums.SegmentMatchType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A saved audience definition — a set of conditions over the tenant's customers.
 * The conditions are stored as a JSON array in {@code conditions_json} and evaluated
 * as a tenant-scoped JPA Specification at query time (see {@code SegmentEvaluator}).
 */
@Entity
@Table(
        name = "marketing_segments",
        indexes = {
                @Index(name = "idx_mkt_segment_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_mkt_segment_owner",   columnList = "tenant_id,owner_user_id"),
                @Index(name = "idx_mkt_segment_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Segment extends BaseTenantEntity implements Ownable {

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private SegmentMatchType matchType = SegmentMatchType.ALL;

    /** JSON array of {field, operator, value} conditions. */
    @Column(name = "conditions_json", columnDefinition = "TEXT")
    private String conditionsJson;
}