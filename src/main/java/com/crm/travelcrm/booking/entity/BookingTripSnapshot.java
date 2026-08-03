package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.lead.enums.DepartureMode;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;
import org.hibernate.envers.NotAudited;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is travelling, from where, and on what — captured at booking time.
 *
 * <p><b>Why this exists.</b> {@code Booking} stored no traveller data at all: no pax counts, no
 * departure, no itinerary. The create form collects all of it, so every submit was throwing that
 * away — the same silent data loss the lead form had. A booking that cannot say how many people are
 * travelling is not a travel booking.
 *
 * <p><b>Why a separate table rather than columns on {@code Booking}.</b> Three reasons. It is ~20
 * columns that only the detail screen reads, and {@code Booking} is loaded on every list, export and
 * report in the system. {@code Booking} is {@code @Audited} (Envers), and widening it widens
 * {@code bookings_aud} on every revision of every booking, forever. And it is a point-in-time
 * SNAPSHOT — deliberately not a live join to the lead — so keeping it structurally separate stops
 * anyone treating it as mutable trip state.
 *
 * <p>{@code @NotAudited}: the snapshot is already immutable-by-intent, so revisioning it would store
 * copies of a thing that does not change.
 *
 * <p>Extends {@link BaseEntity} rather than {@code BaseTenantEntity} for the same reason
 * {@code LeadItinerary} does — it is only ever reached through its {@code Booking}, which is already
 * tenant-scoped, and querying it standalone is not a use case.
 */
@Entity
@Table(name = "booking_trip_snapshot", indexes = {
        @Index(name = "idx_trip_snapshot_booking", columnList = "booking_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Not audited: the class simply carries no @Audited. Envers opts IN, so absence is the whole
// mechanism — @NotAudited is a field/method annotation and is not valid on a type. The exclusion
// that actually matters is on Booking.tripSnapshot, which must be @NotAudited because an audited
// entity may not hold an audited association to a non-audited one.
public class BookingTripSnapshot extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_trip_snapshot_booking"))
    private Booking booking;

    /** Trip category as chosen on the form ("Honeymoon", "Family", …). */
    @Column(name = "package_type", length = 50)
    private String packageType;

    // ── Departure ────────────────────────────────────────────────────────────

    @Column(name = "depart_country", length = 100)
    private String departCountry;

    @Column(name = "depart_city", length = 100)
    private String departCity;

    /** Discriminator — decides which of the three groups below carries data. */
    @Enumerated(EnumType.STRING)
    @Column(name = "departure_mode", length = 20)
    private DepartureMode departureMode;

    @Column(name = "departure_airport", length = 150)
    private String departureAirport;

    @Column(name = "airport_code", length = 10)
    private String airportCode;

    @Column(name = "preferred_flight_time")
    private LocalTime preferredFlightTime;

    @Column(name = "railway_station", length = 150)
    private String railwayStation;

    @Column(name = "train_class", length = 50)
    private String trainClass;

    @Column(name = "preferred_train_time")
    private LocalTime preferredTrainTime;

    @Column(name = "pickup_address", columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "pickup_date_time")
    private LocalDateTime pickupDateTime;

    @Column(name = "vehicle_preference", length = 150)
    private String vehiclePreference;

    // ── Travellers ───────────────────────────────────────────────────────────

    @Column(name = "rooms")
    private Integer rooms;

    @Column(name = "male")
    private Integer male;

    @Column(name = "female")
    private Integer female;

    /** male + female. Stored rather than derived so a later edit to the split cannot silently
        rewrite the headcount this booking was priced against. */
    @Column(name = "total_adults")
    private Integer totalAdults;

    @Column(name = "children")
    private Integer children;

    @Column(name = "infants")
    private Integer infants;

    @Column(name = "extra_beds")
    private Integer extraBeds;

    // ── Accessibility ────────────────────────────────────────────────────────

    @Column(name = "special_assistance_required")
    private Boolean specialAssistanceRequired;

    @Column(name = "assistance_passenger_count")
    private Integer assistancePassengerCount;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "booking_trip_assistance_types",
            joinColumns = @JoinColumn(name = "trip_snapshot_id"))
    @Column(name = "assistance_type")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> specialAssistanceTypes = new ArrayList<>();

    @Column(name = "special_assistance_notes", columnDefinition = "TEXT")
    private String specialAssistanceNotes;

    // ── Itinerary ────────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "tripSnapshot", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<BookingTripLeg> itinerary = new ArrayList<>();

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    public void addLeg(BookingTripLeg leg) {
        leg.setTripSnapshot(this);
        this.itinerary.add(leg);
    }
}
