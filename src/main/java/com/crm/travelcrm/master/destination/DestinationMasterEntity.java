package com.crm.travelcrm.master.destination;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "destination_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DestinationMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "destination_id")
    private Long id;

    @Column(name = "country", nullable = false, length = 100)
    private String country;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(name = "inclusions", columnDefinition = "TEXT")
    private String inclusions;

    @Column(name = "exclusions", columnDefinition = "TEXT")
    private String exclusions;

    @Column(name = "payment_policies", columnDefinition = "TEXT")
    private String paymentPolicies;

    @Column(name = "cancellation_policies", columnDefinition = "TEXT")
    private String cancellationPolicies;

    @Column(name = "booking_terms", columnDefinition = "TEXT")
    private String bookingTerms;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}