package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "leads",
        indexes = {
                @Index(name = "idx_lead_email", columnList = "email"),
                @Index(name = "idx_lead_phone", columnList = "phone"),
                @Index(name = "idx_leads_assigned_user_id", columnList = "assigned_user_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_lead_tenant_email",
                        columnNames = {"tenant_id", "email"}
                ),
                @UniqueConstraint(
                        name = "uk_lead_tenant_phone",
                        columnNames = {"tenant_id", "phone"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Lead extends BaseTenantEntity {
    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email", nullable = false, length = 150)
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "assigned_user_id",
            referencedColumnName = "id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_lead_assigned_user")
    )
    private User assignedUser;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    // Estimated deal value (₹) — drives the Kanban column totals and the
    // active-pipeline figure. Nullable: not every fresh lead has a value yet.
    @Column(name = "estimated_value", precision = 15, scale = 2)
    private BigDecimal estimatedValue;

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

    // Stored as a proper join table (lead_services) via @ElementCollection —
    // one row per service, NOT a comma-separated string column.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lead_services", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "service")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> services = new ArrayList<>();

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<LeadItinerary> itinerary = new ArrayList<>();


    // Convenience method to wire up bi-directional relationship
    public void addItinerary(LeadItinerary item) {
        item.setLead(this);
        this.itinerary.add(item);
    }
}
