package com.crm.travelcrm.portal.itinerary;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.portal.itinerary.dto.*;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.entity.QuotationHotel;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingActivity;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingDay;
import com.crm.travelcrm.quotation.entity.QuotationVehicle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hand-written, whitelist mapper Quotation → traveler-safe itinerary. The single place that decides
 * which itinerary fields a customer may see — every pricing/vendor/internal field is simply never
 * copied. Must run inside the service's read-only transaction so the LAZY collections load.
 */
@Component
public class PortalItineraryMapper {

    public TravelerItineraryDto toItinerary(Booking booking, Quotation q) {
        if (q == null) {
            // No linked quotation — return the booking-derived header only.
            return TravelerItineraryDto.builder()
                    .available(false)
                    .destination(booking.getDestinationSnapshot())
                    .travelDate(booking.getTravelDate())
                    .hotels(List.of())
                    .vehicles(List.of())
                    .days(List.of())
                    .inclusions(List.of())
                    .exclusions(List.of())
                    .paymentPolicies(List.of())
                    .cancellationPolicies(List.of())
                    .build();
        }
        return TravelerItineraryDto.builder()
                .available(true)
                .title(q.getTitle())
                .destination(q.getDestination() != null ? q.getDestination() : booking.getDestinationSnapshot())
                .travelDate(q.getTravelDate() != null ? q.getTravelDate() : booking.getTravelDate())
                .adults(q.getAdults())
                .children(q.getChildren())
                .infants(q.getInfants())
                .hotels(mapHotels(q.getHotels()))
                .vehicles(mapVehicles(q.getVehicles()))
                .days(mapDays(q.getSightseeingDays()))
                .inclusions(safe(q.getInclusions()))
                .exclusions(safe(q.getExclusions()))
                .paymentPolicies(safe(q.getPaymentPolicies()))
                .cancellationPolicies(safe(q.getCancellationPolicies()))
                .build();
    }

    private List<ItineraryHotelDto> mapHotels(List<QuotationHotel> hotels) {
        if (hotels == null) return List.of();
        List<ItineraryHotelDto> out = new ArrayList<>(hotels.size());
        for (QuotationHotel h : hotels) {
            out.add(ItineraryHotelDto.builder()
                    .name(h.getName())
                    .city(h.getCity())
                    .stars(h.getStars())
                    .roomType(h.getRoomType())
                    .mealPlan(h.getMealPlan())
                    .checkIn(h.getCheckIn())
                    .checkOut(h.getCheckOut())
                    .refundable(h.getRefundable())
                    .imagePath(h.getImagePath())
                    .build());
        }
        return out;
    }

    private List<ItineraryVehicleDto> mapVehicles(List<QuotationVehicle> vehicles) {
        if (vehicles == null) return List.of();
        List<ItineraryVehicleDto> out = new ArrayList<>(vehicles.size());
        for (QuotationVehicle v : vehicles) {
            out.add(ItineraryVehicleDto.builder()
                    .type(v.getType())
                    .pickup(v.getPickup())
                    .drop(v.getDrop())
                    .startDate(v.getStartDate())
                    .endDate(v.getEndDate())
                    .build());
        }
        return out;
    }

    private List<ItineraryDayDto> mapDays(List<QuotationSightseeingDay> days) {
        if (days == null) return List.of();
        List<ItineraryDayDto> out = new ArrayList<>(days.size());
        for (QuotationSightseeingDay d : days) {
            out.add(ItineraryDayDto.builder()
                    .dayNumber(d.getDayNumber())
                    .date(d.getDate())
                    .activities(mapActivities(d.getActivities()))
                    .build());
        }
        // Order by day number (nulls last) for a stable timeline.
        out.sort(Comparator.comparing(ItineraryDayDto::getDayNumber,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private List<ItineraryActivityDto> mapActivities(List<QuotationSightseeingActivity> activities) {
        if (activities == null) return List.of();
        List<ItineraryActivityDto> out = new ArrayList<>(activities.size());
        for (QuotationSightseeingActivity a : activities) {
            out.add(ItineraryActivityDto.builder()
                    .attraction(a.getAttraction())
                    .startTime(a.getStartTime())
                    .description(a.getDescription())
                    .transfer(a.getTransfer())
                    .meals(safe(a.getMeals()))
                    .imagePath(a.getImagePath())
                    .build());
        }
        return out;
    }

    private List<String> safe(List<String> in) {
        return in == null ? List.of() : new ArrayList<>(in);
    }
}