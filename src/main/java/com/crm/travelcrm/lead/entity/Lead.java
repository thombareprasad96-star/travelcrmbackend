package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "leads", indexes = {
        @Index(name = "idx_lead_email", columnList = "email"),
        @Index(name = "idx_lead_phone", columnList = "phone")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "phone", nullable = false, unique = true, length = 20)
    private String phone;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source", nullable = false, length = 50)
    private LeadSource leadSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_type", nullable = false, length = 50)
    private LeadType leadType;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_stage", nullable = false, length = 50)
    private LeadStage leadStage;

    @Column(name = "assign_to", length = 100)
    private String assignTo;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    @Column(name = "depart_country", length = 100)
    private String departCountry;

    @Column(name = "depart_city", length = 100)
    private String departCity;

    @Column(name = "rooms")
    private Integer rooms;

    @Column(name = "adults")
    private Integer adults;

    @Column(name = "children")
    private Integer children;

    @Column(name = "infants")
    private Integer infants;

    @Column(name = "extra_beds")
    private Integer extraBeds;

    // Stored as comma-separated values in a single column for simplicity
    // Use @ElementCollection for a proper join table approach
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lead_services", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "service")
    @Builder.Default
    private List<String> services = new ArrayList<>();

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<LeadItinerary> itinerary = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Convenience method to wire up bi-directional relationship
    public void addItinerary(LeadItinerary item) {
        item.setLead(this);
        this.itinerary.add(item);
    }
}