package com.crm.travelcrm.quotation.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A cruise line item within a quotation.
 */
@Entity
@Table(name = "quotation_cruises", indexes = {
        @Index(name = "idx_qcruise_quotation", columnList = "quotation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationCruise extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_qcruise_quotation"))
    private Quotation quotation;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "type", length = 80)
    private String type;

    @Column(name = "dep_port", length = 120)
    private String depPort;

    @Column(name = "arr_port", length = 120)
    private String arrPort;

    @Column(name = "dep_date", length = 30)
    private String depDate;

    @Column(name = "nights")
    private Integer nights;

    @Column(name = "cabin", length = 80)
    private String cabin;

    @Column(name = "price", precision = 15, scale = 2)
    private BigDecimal price;

    // Optional richer pricing (newer tab fields)
    @Column(name = "price_per_pax", precision = 15, scale = 2)
    private BigDecimal pricePerPax;

    @Column(name = "pax")
    private Integer pax;
}