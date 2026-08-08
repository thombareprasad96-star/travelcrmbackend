package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Room-wise occupancy requested on a lead.
 *
 * <p>This deliberately stores generic preferences, not a Hotel Master room id. Hotel room types
 * belong to one specific hotel and cannot be selected truthfully until the quotation chooses that
 * hotel. Quick Quote uses these allocations to build room lines, then maps each line to the chosen
 * hotel's actual room-type and meal-plan masters.</p>
 */
@Entity
@Table(name = "lead_room_allocations", indexes = {
        @Index(name = "idx_lead_room_allocation_lead", columnList = "lead_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadRoomAllocation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lead_room_allocation_lead"))
    private Lead lead;

    @Column(name = "room_number", nullable = false)
    private Integer roomNumber;

    @Column(name = "room_category_preference", length = 80)
    private String roomCategoryPreference;

    @Column(name = "bed_preference", length = 80)
    private String bedPreference;

    @Column(name = "adults", nullable = false)
    private Integer adults;

    @Column(name = "children", nullable = false)
    private Integer children;

    @Column(name = "infants", nullable = false)
    private Integer infants;

    @Column(name = "extra_beds", nullable = false)
    private Integer extraBeds;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lead_room_allocation_child_ages",
            joinColumns = @JoinColumn(name = "room_allocation_id"))
    @OrderColumn(name = "age_order")
    @Column(name = "child_age")
    @BatchSize(size = 50)
    @Builder.Default
    private List<Integer> childAges = new ArrayList<>();
}
