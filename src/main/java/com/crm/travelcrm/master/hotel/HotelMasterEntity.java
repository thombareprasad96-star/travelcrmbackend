//package com.crm.travelcrm.master.hotel;
//
//import jakarta.persistence.*;
//import lombok.*;
//import org.hibernate.annotations.BatchSize;
//
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.List;
//
//@Entity
//@Table(name = "hotel_master",
//        indexes = {
//                @Index(name = "idx_hotel_tenant", columnList = "tenant_id"),
//                @Index(name = "idx_hotel_destination", columnList = "destination_id"),
//                @Index(name = "idx_hotel_tenant_destination", columnList = "tenant_id, destination_id")
//        })
//@Getter
//@Setter
//@NoArgsConstructor
//@AllArgsConstructor
//@Builder
//public class HotelMasterEntity {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Column(name = "hotel_id")
//    private Long id;
//
//    // References destination_master.destination_id. Stored as a plain id because the master
//    // modules are deliberately standalone (no cross-entity JPA relations). The destination's
//    // visibility to the caller is validated in the service layer on every write.
//    @Column(name = "destination_id", nullable = false)
//    private Long destinationId;
//
//    @Column(name = "name", nullable = false, length = 200)
//    private String name;
//
//    @Column(name = "city", length = 100)
//    private String city;
//
//    @Column(name = "stars")
//    private Integer stars;
//
//    @Column(name = "rating")
//    private Double rating;
//
//    @Column(name = "is_default", nullable = false)
//    @Builder.Default
//    private boolean isDefault = false;
//
//    @Column(name = "address", length = 500)
//    private String address;
//
//    @Column(name = "map_url", length = 500)
//    private String mapUrl;
//
//    @Column(name = "latitude")
//    private Double latitude;
//
//    @Column(name = "longitude")
//    private Double longitude;
//
//    @Column(name = "contact_person", length = 150)
//    private String contactPerson;
//
//    @Column(name = "phone", length = 30)
//    private String phone;
//
//    @Column(name = "email", length = 150)
//    private String email;
//
//    @Column(name = "website", length = 200)
//    private String website;
//
//    @Column(name = "overview", columnDefinition = "TEXT")
//    private String overview;
//
//    // Cloudinary secure_url — image bytes never reach the backend (see Destination master).
//    @Column(name = "image_url", length = 500)
//    private String imageUrl;
//
//    @ElementCollection(fetch = FetchType.LAZY)
//    @CollectionTable(name = "hotel_amenity", joinColumns = @JoinColumn(name = "hotel_id"))
//    @Column(name = "amenity", length = 50)
//    @BatchSize(size = 50)
//    @Builder.Default
//    private List<String> amenities = new ArrayList<>();
//
//    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
//    @BatchSize(size = 50)
//    @Builder.Default
//    private List<RoomTypeEntity> roomTypes = new ArrayList<>();
//
//    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true)
//    @BatchSize(size = 50)
//    @Builder.Default
//    private List<MealPlanEntity> mealPlans = new ArrayList<>();
//
//    // null = global hotel (platform-managed, visible to all tenants);
//    // non-null = owned by that tenant only. Intentionally NOT a BaseTenantEntity — its
//    // strict tenantFilter would hide global rows from tenant users. Mirrors City/Destination.
//    @Column(name = "tenant_id")
//    private Long tenantId;
//
//    @Column(name = "created_at", nullable = false, updatable = false)
//    private LocalDateTime createdAt;
//
//    @PrePersist
//    public void prePersist() {
//        this.createdAt = LocalDateTime.now();
//    }
//
//    // Keep both sides of the bidirectional relation consistent so cascade persist works.
//    public void addRoomType(RoomTypeEntity room) {
//        room.setHotel(this);
//        this.roomTypes.add(room);
//    }
//
//    public void addMealPlan(MealPlanEntity meal) {
//        meal.setHotel(this);
//        this.mealPlans.add(meal);
//    }
//}