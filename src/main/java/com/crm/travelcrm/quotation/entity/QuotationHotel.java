package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A hotel option within a quotation.
 */
@Entity
@Table(name = "quotation_hotels", indexes = {
        @Index(name = "idx_qhotel_quotation", columnList = "quotation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationHotel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qhotel_quotation"))
    private Quotation quotation;

    // Logical master references plus immutable text/rate snapshots below. Never use FKs here:
    // a master can be edited or trashed while an issued quotation must keep rendering unchanged.
    @Column(name = "hotel_master_public_id")
    private UUID hotelMasterPublicId;

    @Column(name = "room_type_master_public_id")
    private UUID roomTypeMasterPublicId;

    @Column(name = "meal_plan_master_public_id")
    private UUID mealPlanMasterPublicId;

    // Canonical marketplace source snapshots. Present only for PLATFORM_SYNC selections; keeping
    // these separate from tenant-master IDs prevents a later un-import from breaking booking intent.
    @Column(name = "platform_hotel_public_id")
    private UUID platformHotelPublicId;

    @Column(name = "platform_room_public_id")
    private UUID platformRoomPublicId;

    @Column(name = "platform_meal_plan_public_id")
    private UUID platformMealPlanPublicId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "check_in", length = 30)
    private String checkIn;

    @Column(name = "check_out", length = 30)
    private String checkOut;

    @Column(name = "room_type", length = 80)
    private String roomType;

    @Column(name = "meal_plan", length = 80)
    private String mealPlan;

    @Column(name = "bed_type", length = 100)
    private String bedType;

    @Column(name = "room_occupancy")
    private Integer occupancy;

    @Column(name = "allocated_adults")
    private Integer adults;

    @Column(name = "allocated_children")
    private Integer children;

    @Column(name = "allocated_infants")
    private Integer infants;

    @Column(name = "allocated_extra_beds")
    private Integer extraBeds;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quotation_hotel_child_ages",
            joinColumns = @JoinColumn(name = "quotation_hotel_id"))
    @OrderColumn(name = "age_order")
    @Column(name = "child_age")
    @Builder.Default
    private List<Integer> childAges = new ArrayList<>();

    /** MASTER / MANUAL / MISSING at the moment this quotation line was saved. */
    @Column(name = "rate_source", length = 20)
    private String rateSource;

    @Column(name = "refundable")
    private Boolean refundable;

    /** Star rating (1–5), optional. */
    @Column(name = "stars")
    private Integer stars;

    // Optional richer pricing (newer tab fields)
    @Column(name = "price_per_room", precision = 15, scale = 2)
    private BigDecimal pricePerRoom;

    @Column(name = "rooms")
    private Integer rooms;

    /** Optional hotel image (Cloudinary URL) rendered in the PDF when present. */
    @Column(name = "image_url", length = 500)
    private String imagePath;
}
