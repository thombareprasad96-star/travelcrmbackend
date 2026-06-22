package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

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
    private String imageUrl;
}