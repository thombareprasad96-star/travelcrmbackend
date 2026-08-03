package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One leg of the booked itinerary — "3 nights in Calangute, Goa", day 1.
 *
 * <p>Mirrors {@code LeadItinerary} field for field so lead-to-booking conversion is a straight copy
 * rather than a translation. Names are stored, not ids: a booking must keep printing the itinerary
 * it was sold with even if the destination master is later renamed or deleted.
 */
@Entity
@Table(name = "booking_trip_legs", indexes = {
        @Index(name = "idx_trip_leg_snapshot", columnList = "trip_snapshot_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Not audited — Envers opts in, so carrying no @Audited is the exclusion. See BookingTripSnapshot.
public class BookingTripLeg extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_snapshot_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_trip_leg_snapshot"))
    private BookingTripSnapshot tripSnapshot;

    @Column(name = "destination", length = 100)
    private String destination;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "nights")
    private Integer nights;

    @Column(name = "day_number")
    private Integer dayNumber;
}
