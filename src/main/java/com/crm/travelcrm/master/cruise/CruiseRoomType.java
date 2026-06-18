package com.crm.travelcrm.master.cruise;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Entity
@Table(name = "cruise_room_types",
        indexes = @Index(name = "idx_cruise_room_cruise", columnList = "cruise_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CruiseRoomType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cruise_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_cruise_room_cruise"))
    private Cruise cruise;

    @Column(nullable = false, length = 150)
    private String name;

    @Column
    private Integer capacity;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;
}