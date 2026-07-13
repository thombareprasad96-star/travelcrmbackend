package com.crm.travelcrm.quotationtemplate.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A hotel line in a template. Field-for-field the shape of {@code QuotationHotel} minus the
 * check-in/check-out dates, which only exist once a lead's travel date is known — the apply flow
 * walks {@link #nights} forward from {@code Lead.travelDate} to fill them in.
 *
 * <p>Deliberately stores no {@code hotelId}: quotations are denormalized name snapshots, so a
 * template that carried a master FK would silently diverge from the quotation it clones into (and
 * would break the day a hotel is deleted). {@link #stars} is what the matcher cares about anyway.
 */
@Entity
@Table(
        name = "quotation_template_hotels",
        indexes = @Index(name = "idx_qtpl_hotel_template", columnList = "template_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationTemplateHotel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "template_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_qtpl_hotel_template"))
    private QuotationTemplate template;

    /** Render/apply order. Also the order the check-in dates are walked in. */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "stars")
    private Integer stars;

    @Column(name = "room_type", length = 80)
    private String roomType;

    @Column(name = "meal_plan", length = 80)
    private String mealPlan;

    @Column(name = "refundable")
    private Boolean refundable;

    @Column(name = "price_per_room", precision = 15, scale = 2)
    private BigDecimal pricePerRoom;

    /** Null means "however many rooms the lead needs" — resolved at apply time. */
    @Column(name = "rooms")
    private Integer rooms;

    /** Nights at this hotel; drives both the amount and the check-out date. */
    @Column(name = "nights")
    private Integer nights;

    @Column(name = "image_path", length = 500)
    private String imagePath;
}