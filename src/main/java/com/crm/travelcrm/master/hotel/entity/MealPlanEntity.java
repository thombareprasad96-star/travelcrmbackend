package com.crm.travelcrm.master.hotel.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "hotel_meal_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MealPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotel_id", nullable = false)
    private HotelEntity hotel;

    @Column(nullable = false, length = 100)
    private String name;

    private Double price;

    @Column(columnDefinition = "TEXT")
    private String description;
}