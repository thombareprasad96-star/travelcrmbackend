package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotel_room_types",
        indexes = @Index(name = "idx_room_type_hotel", columnList = "hotel_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RoomType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_room_type_hotel"))
    private Hotel hotel;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String size;

    @Column
    private Integer occupancy;

    @Column(name = "bed_type", length = 100)
    private String bedType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection
    @CollectionTable(name = "room_type_images",
            joinColumns = @JoinColumn(name = "room_type_id"))
    @Column(name = "image_url", length = 500)
    @Builder.Default
    private List<String> images = new ArrayList<>();
}