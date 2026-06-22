package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A single day of the sightseeing itinerary, holding one or more activities.
 */
@Entity
@Table(name = "quotation_sightseeing_days", indexes = {
        @Index(name = "idx_qsday_quotation", columnList = "quotation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationSightseeingDay extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qsday_quotation"))
    private Quotation quotation;

    @Column(name = "day_number")
    private Integer dayNumber;        // JSON "day"

    @Column(name = "day_date", length = 30)
    private String date;

    // Optional richer pricing (newer tab fields)
    @Column(name = "price_per_pax", precision = 15, scale = 2)
    private BigDecimal pricePerPax;

    @Column(name = "pax")
    private Integer pax;

    @OneToMany(mappedBy = "day", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationSightseeingActivity> activities = new ArrayList<>();

    public void addActivity(QuotationSightseeingActivity a) {
        a.setDay(this);
        this.activities.add(a);
    }
}