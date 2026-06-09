package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "lead_itinerary", indexes = {
        @Index(name = "idx_itinerary_lead", columnList = "lead_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadItinerary extends BaseEntity {
    // Extends BaseEntity (not BaseTenantEntity) because it is always
    // accessed through its parent Lead which already has tenant_id.
    // Querying itinerary directly without going through Lead is not a use case.

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Column(name = "destination", nullable = false, length = 100)
    private String destination;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "nights", nullable = false)
    private Integer nights;

    @Column(name = "day_number")
    private Integer dayNumber;       // sequence order within the itinerary
}