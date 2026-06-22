package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * An activity within a {@link QuotationSightseeingDay}.
 */
@Entity
@Table(name = "quotation_sightseeing_activities", indexes = {
        @Index(name = "idx_qsact_day", columnList = "day_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationSightseeingActivity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "day_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qsact_day"))
    private QuotationSightseeingDay day;

    @Column(name = "attraction", length = 200)
    private String attraction;

    @Column(name = "start_time", length = 20)
    private String startTime;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "transfer", length = 40)
    private String transfer;

    /** Meals included for this activity, e.g. ["Breakfast","Lunch"]. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quotation_activity_meals", joinColumns = @JoinColumn(name = "activity_id"))
    @Column(name = "meal", length = 40)
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> meals = new ArrayList<>();

    /** Optional activity image (Cloudinary URL) rendered in the PDF when present. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;
}