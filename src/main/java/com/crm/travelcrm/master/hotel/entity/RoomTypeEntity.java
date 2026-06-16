package com.crm.travelcrm.master.hotel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotel_room_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoomTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotel_id", nullable = false)
    private HotelEntity hotel;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String size;

    private Integer occupancy;

    @Column(length = 50)
    private String bedType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ElementCollection
    @CollectionTable(name = "room_type_images", joinColumns = @JoinColumn(name = "room_type_id"))
    @Column(name = "image_url")
    @Builder.Default
    private List<String> images = new ArrayList<>();
}