package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.master.geography.entity.City;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "hotels",
        indexes = {
                @Index(name = "idx_hotel_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_hotel_city",    columnList = "city_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Hide trashed rows from every read (see softDeleteFilter on BaseTenantEntity).
@Filter(name = "softDeleteFilter", condition = "deleted_at is null")
public class Hotel extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_hotel_city"))
    private City city;

    @Column(nullable = false, length = 200)
    private String name;

    @Column
    private Integer stars;

    @Column
    private Double rating;

    @Column(length = 500)
    private String address;

    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 500)
    private String website;

    @Column(name = "map_url", length = 500)
    private String mapUrl;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @ElementCollection
    @CollectionTable(name = "hotel_amenities",
            joinColumns = @JoinColumn(name = "hotel_id"))
    @Column(name = "amenity", length = 200)
    @Builder.Default
    private List<String> amenities = new ArrayList<>();

    @Column(name = "is_default")
    private boolean isDefault;

    @Column(name = "image_path", length = 500)
    private String imagePath;

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RoomType> roomTypes = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MealPlan> mealPlans = new ArrayList<>();
}