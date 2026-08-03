package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.lead.enums.DepartureMode;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The {@code tripSnapshot} block of the create-booking payload — who is travelling, from where, and
 * the route.
 *
 * <p>The nesting here is not decorative: it mirrors the shape the form already posts
 * ({@code departure} / {@code travellers} / {@code specialAssistance} / {@code itinerary}), so the
 * contract is the payload rather than a flattened re-interpretation of it.
 *
 * <p>Every field is optional. This block is additive context on a booking whose money fields are
 * what actually matter — rejecting a booking because the pax split was left blank would block a
 * sale over a detail that can be filled in later.
 */
@Data
public class TripSnapshotRequest {

    @Size(max = 50, message = "Package type must not exceed 50 characters")
    private String packageType;

    @Valid
    private Departure departure;

    @Valid
    private Travellers travellers;

    @Valid
    private SpecialAssistance specialAssistance;

    @Valid
    private List<TripLeg> itinerary;

    @Size(max = 4000, message = "Trip notes must not exceed 4000 characters")
    private String notes;

    /**
     * Outbound departure. {@code mode} is the discriminator; the service clears whichever of the
     * three field groups it does not select, so switching mode on the form cannot leave a train
     * booking carrying an airport code.
     */
    @Data
    public static class Departure {

        @Size(max = 100)
        private String country;

        @Size(max = 100)
        private String city;

        /** "Flight / Airport" | "Train / Rail" | "Car / Road" | "Bus" | "Other". */
        private DepartureMode mode;

        // Flight
        @Size(max = 150)
        private String airport;

        @Size(max = 10)
        private String airportCode;

        // Shared by the flight and train groups — the form sends both under this one key, and which
        // column it lands in is decided by `mode`. A single key for two fields is why the service
        // must read `mode` first rather than copying field-by-field.
        @JsonFormat(pattern = "HH:mm")
        private LocalTime preferredTime;

        // Train
        @Size(max = 150)
        private String railwayStation;

        @Size(max = 50)
        private String trainClass;

        // Car
        @Size(max = 1000)
        private String pickupAddress;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        private LocalDateTime pickupDateTime;

        @Size(max = 150)
        private String vehiclePreference;
    }

    @Data
    public static class Travellers {
        @Min(value = 0, message = "Rooms cannot be negative")
        private Integer rooms;

        @Min(value = 0, message = "Male count cannot be negative")
        private Integer male;

        @Min(value = 0, message = "Female count cannot be negative")
        private Integer female;

        @Min(value = 0, message = "Adult count cannot be negative")
        private Integer totalAdults;

        @Min(value = 0, message = "Children count cannot be negative")
        private Integer children;

        @Min(value = 0, message = "Infants count cannot be negative")
        private Integer infants;

        @Min(value = 0, message = "Extra beds cannot be negative")
        private Integer extraBeds;
    }

    @Data
    public static class SpecialAssistance {
        private Boolean required;

        private List<String> types;

        @Min(value = 0, message = "Assistance passenger count cannot be negative")
        private Integer passengerCount;

        @Size(max = 2000, message = "Assistance notes must not exceed 2000 characters")
        private String notes;
    }

    @Data
    public static class TripLeg {
        @Size(max = 100)
        private String destination;

        @Size(max = 100)
        private String city;

        @Min(value = 0, message = "Nights cannot be negative")
        private Integer nights;

        @Min(value = 1, message = "Day number must be at least 1")
        private Integer dayNumber;
    }
}
