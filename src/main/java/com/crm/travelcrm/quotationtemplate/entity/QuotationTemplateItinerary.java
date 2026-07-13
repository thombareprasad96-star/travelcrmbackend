package com.crm.travelcrm.quotationtemplate.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One day of a template's itinerary. Extends {@link BaseEntity}, not {@code BaseTenantEntity} —
 * it is only ever reached through its tenant-scoped parent, exactly like {@code QuotationHotel}.
 *
 * <p>{@link #cityId} is the one place this module keeps a master id. Destination scoring compares
 * city identities, so "Delhi" typed into a lead has to resolve to the same thing as the Delhi an
 * agent picked from the dropdown when authoring the template. {@link #cityName} and
 * {@link #destinationName} are snapshots for display and for the name-fallback comparison when a
 * lead's free-typed city never resolves to a master row.
 */
@Entity
@Table(
        name = "quotation_template_itinerary",
        indexes = {
                @Index(name = "idx_qtpl_itin_template", columnList = "template_id"),
                @Index(name = "idx_qtpl_itin_city", columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationTemplateItinerary extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "template_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_qtpl_itinerary_template"))
    private QuotationTemplate template;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    /** Master {@code City.id}, validated tenant-owned in the service. No DB FK, by convention. */
    @Column(name = "city_id")
    private Long cityId;

    @Column(name = "city_name", length = 120)
    private String cityName;

    @Column(name = "destination_name", length = 120)
    private String destinationName;

    @Column(name = "nights")
    private Integer nights;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Becomes {@code QuotationSightseeingDay.pricePerPax} when the template is applied. */
    @Column(name = "price_per_pax", precision = 15, scale = 2)
    private BigDecimal pricePerPax;
}