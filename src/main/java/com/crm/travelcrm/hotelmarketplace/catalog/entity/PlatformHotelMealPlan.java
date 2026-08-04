package com.crm.travelcrm.hotelmarketplace.catalog.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.MealPlanCode;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A meal plan offered by a catalog hotel.
 *
 * <p><b>No price column</b> — a meal plan is an inclusion, not a rate. The tenant's own
 * {@code MealPlan} master does carry a price, and treating that price as the room rate is precisely
 * the confusion the catalog is built to avoid: final pricing belongs to the rate calendar.</p>
 *
 * <p>{@link #code} is the stable machine value the sync matches on; {@link #name} is what a human
 * reads and may be edited freely without breaking the link.</p>
 */
@Entity
@Table(name = "platform_hotel_meal_plans",
        indexes = @Index(name = "idx_platform_meal_plan_hotel", columnList = "platform_hotel_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformHotelMealPlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "platform_hotel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_platform_meal_plan_hotel"))
    private PlatformHotel hotel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MealPlanCode code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
