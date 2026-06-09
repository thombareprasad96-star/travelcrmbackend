package com.crm.travelcrm.master.city;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "city_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CityMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "city_id")
    private Long id;

    @Column(name = "country_name", nullable = false, length = 100)
    private String country;

    @Column(name = "city_name", nullable = false, length = 100)
    private String city;

    @Column(name = "airport_code", length = 10)
    private String airportCode;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

}