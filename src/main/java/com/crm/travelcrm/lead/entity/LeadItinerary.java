package com.crm.travelcrm.lead.entity;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lead_itinerary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadItinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private Lead lead;

    @Column(name = "destination", nullable = false, length = 100)
    private String destination;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "nights", nullable = false)
    private Integer nights;
}