package com.crm.travelcrm.hotelmarketplace.catalog.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * A sellable room category on a catalog hotel.
 *
 * <p><b>No price column, on purpose.</b> A room's price depends on the date, the meal plan and the
 * occupancy, so it belongs to a rate plan and a dated rate calendar. A "base price" sitting here
 * would be the number somebody eventually bills against, and it would be wrong on most dates.</p>
 *
 * <p>{@code active} rather than deletion: a room that stops selling still has to resolve for every
 * historical booking that names it.</p>
 */
@Entity
@Table(name = "platform_hotel_rooms",
        indexes = @Index(name = "idx_platform_room_hotel", columnList = "platform_hotel_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformHotelRoom extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "platform_hotel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_platform_room_hotel"))
    private PlatformHotel hotel;

    @Column(nullable = false, length = 150)
    private String name;

    // ── Occupancy. Validated at booking time; a room that sleeps 2 must reject 4 guests. ──

    @Column(name = "max_adults")
    private Integer maxAdults;

    @Column(name = "max_children")
    private Integer maxChildren;

    @Column(name = "max_occupancy")
    private Integer maxOccupancy;

    @Column(name = "bed_type", length = 100)
    private String bedType;

    @Column(length = 100)
    private String size;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "platform_hotel_room_images",
            joinColumns = @JoinColumn(name = "platform_room_id"))
    @Column(name = "image_url", length = 500)
    @Builder.Default
    private List<String> images = new ArrayList<>();
}
