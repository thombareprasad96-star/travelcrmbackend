package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A connecting flight nested under a {@link QuotationFlightSegment}.
 */
@Entity
@Table(name = "quotation_flight_connections", indexes = {
        @Index(name = "idx_qfc_segment", columnList = "segment_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationFlightConnection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "segment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qfc_segment"))
    private QuotationFlightSegment segment;

    @Column(name = "airline", length = 120)
    private String airline;

    @Column(name = "flight_no", length = 40)
    private String flightNo;

    @Column(name = "from_location", length = 120)
    private String fromLocation;      // JSON "from"

    @Column(name = "to_location", length = 120)
    private String toLocation;        // JSON "to"

    @Column(name = "dep_date", length = 30)
    private String depDate;

    @Column(name = "dep_time", length = 20)
    private String depTime;

    @Column(name = "arr_date", length = 30)
    private String arrDate;

    @Column(name = "arr_time", length = 20)
    private String arrTime;
}