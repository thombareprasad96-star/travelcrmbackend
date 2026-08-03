package com.crm.travelcrm.booking.dto.response;

import com.crm.travelcrm.lead.enums.DepartureMode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Read-back of {@code booking_trip_snapshot}, nested on {@link BookingResponseDTO}.
 *
 * <p>Shaped to mirror the request ({@code departure} / {@code travellers} / {@code specialAssistance}
 * / {@code itinerary}) so the booking detail screen and the create form speak one vocabulary, and a
 * round-trip needs no translation layer on either side.
 *
 * <p>Carries no financial or margin data — it is trip facts only, so it is safe to include in any
 * view a user is allowed to see the booking in.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TripSnapshotResponse {

    String packageType;
    Departure departure;
    Travellers travellers;
    SpecialAssistance specialAssistance;
    List<TripLeg> itinerary;
    String notes;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Departure {
        String country;
        String city;
        DepartureMode mode;

        String airport;
        String airportCode;

        String railwayStation;
        String trainClass;

        /**
         * The flight or train preferred time, whichever the {@code mode} selects — the same single
         * key the form posts, so what goes out matches what came in.
         */
        @JsonFormat(pattern = "HH:mm")
        LocalTime preferredTime;

        String pickupAddress;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        LocalDateTime pickupDateTime;
        String vehiclePreference;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Travellers {
        Integer rooms;
        Integer male;
        Integer female;
        Integer totalAdults;
        Integer children;
        Integer infants;
        Integer extraBeds;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SpecialAssistance {
        Boolean required;
        List<String> types;
        Integer passengerCount;
        String notes;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TripLeg {
        String destination;
        String city;
        Integer nights;
        Integer dayNumber;
    }
}
