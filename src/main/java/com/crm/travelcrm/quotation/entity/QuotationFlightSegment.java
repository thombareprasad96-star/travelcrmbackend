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
 * One flight leg of a quotation (the frontend "Segment"). Extends
 * {@link BaseEntity} — always reached through its parent {@link Quotation},
 * which carries the tenant id.
 */
@Entity
@Table(name = "quotation_flight_segments", indexes = {
        @Index(name = "idx_qfs_quotation", columnList = "quotation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationFlightSegment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qfs_quotation"))
    private Quotation quotation;

    @Column(name = "airline", length = 120)
    private String airline;

    @Column(name = "flight_no", length = 40)
    private String flightNo;

    @Column(name = "travel_class", length = 40)
    private String travelClass;       // JSON "class"

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

    @Column(name = "duration", length = 40)
    private String duration;

    @Column(name = "cabin_baggage")
    private Integer cabinBaggage;     // JSON "cabin" (kg)

    @Column(name = "checkin_baggage")
    private Integer checkinBaggage;   // JSON "checkin" (kg)

    // Optional richer per-segment pricing (newer tab fields)
    @Column(name = "price_per_pax", precision = 15, scale = 2)
    private BigDecimal pricePerPax;

    @Column(name = "pax")
    private Integer pax;

    @OneToMany(mappedBy = "segment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<QuotationFlightConnection> connections = new ArrayList<>();

    public void addConnection(QuotationFlightConnection c) {
        c.setSegment(this);
        this.connections.add(c);
    }
}