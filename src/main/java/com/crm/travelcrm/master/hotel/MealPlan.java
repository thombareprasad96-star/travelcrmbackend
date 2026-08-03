package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "hotel_meal_plans",
        indexes = @Index(name = "idx_meal_plan_hotel", columnList = "hotel_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MealPlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_meal_plan_hotel"))
    private Hotel hotel;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    /**
     * The catalog meal plan this row projects, when the parent hotel was imported. Lets a re-sync
     * UPSERT the row instead of deleting and recreating it — recreating would mint a new publicId
     * on every sync and orphan anything that referenced the previous one.
     */
    @Column(name = "platform_source_public_id")
    private UUID platformSourcePublicId;

    /** Withdrawn plans stay for history; only active ones are offered. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}