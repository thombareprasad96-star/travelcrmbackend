package com.crm.travelcrm.master.hotel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotels")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HotelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "destination_id", nullable = false)
    private Long destinationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String city;

    private Integer stars;

    private Double rating;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 500)
    private String mapUrl;

    private Double latitude;

    private Double longitude;

    @Column(length = 150)
    private String contactPerson;

    @Column(length = 50)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(length = 255)
    private String website;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @Column(length = 500)
    private String imageUrl;

    @ElementCollection
    @CollectionTable(name = "hotel_amenities", joinColumns = @JoinColumn(name = "hotel_id"))
    @Column(name = "amenity")
    @Builder.Default
    private List<String> amenities = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoomTypeEntity> roomTypes = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MealPlanEntity> mealPlans = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}