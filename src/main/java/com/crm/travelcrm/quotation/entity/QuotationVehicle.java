package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A vehicle/transfer line item within a quotation.
 */
@Entity
@Table(name = "quotation_vehicles", indexes = {
        @Index(name = "idx_qvehicle_quotation", columnList = "quotation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationVehicle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qvehicle_quotation"))
    private Quotation quotation;

    @Column(name = "type", length = 80)
    private String type;

    @Column(name = "pickup_location", length = 200)
    private String pickup;

    @Column(name = "drop_location", length = 200)
    private String drop;

    @Column(name = "start_date", length = 30)
    private String startDate;

    @Column(name = "end_date", length = 30)
    private String endDate;

    @Column(name = "price", precision = 15, scale = 2)
    private BigDecimal price;

    // Optional richer pricing (newer tab fields)
    @Column(name = "price_per_vehicle", precision = 15, scale = 2)
    private BigDecimal pricePerVehicle;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
