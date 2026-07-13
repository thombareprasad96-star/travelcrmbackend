package com.crm.travelcrm.quotationtemplate.mapper;

import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateResponse;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchResponse;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateHotel;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateItinerary;
import com.crm.travelcrm.quotationtemplate.matching.CityRef;
import com.crm.travelcrm.quotationtemplate.matching.MatchScore;
import com.crm.travelcrm.quotationtemplate.matching.TemplateProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Hand-written rather than MapStruct: two of the three outputs here are projections that reshape
 * the aggregate (a {@link TemplateProfile} flattens the itinerary into a city list; a match response
 * fuses an entity with a score), which is exactly the case MapStruct makes awkward.
 *
 * <p>Every method touches lazy collections, so callers must be inside a transaction.
 */
@Component
public class QuotationTemplateMapper {

    // ── Entity → API ──────────────────────────────────────────────────────────

    public QuotationTemplateResponse toResponse(QuotationTemplate t) {
        return QuotationTemplateResponse.builder()
                .id(t.getPublicId())
                .name(t.getName())
                .description(t.getDescription())
                .coverImageUrl(t.getCoverImageUrl())
                .active(t.getActive())
                .durationNights(t.getDurationNights())
                .durationDays(t.getDurationDays())
                .hotelTier(t.getHotelTier())
                .basePrice(t.getBasePrice())
                .seasonMonths(sortedMonths(t.getSeasonMonths()))
                .cities(cityNames(t))
                .itinerary(t.getItinerary().stream().map(this::toDay).toList())
                .hotels(t.getHotels().stream().map(this::toHotel).toList())
                .inclusions(List.copyOf(t.getInclusions()))
                .exclusions(List.copyOf(t.getExclusions()))
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private QuotationTemplateResponse.ItineraryDay toDay(QuotationTemplateItinerary d) {
        return QuotationTemplateResponse.ItineraryDay.builder()
                .id(d.getPublicId())
                .dayNumber(d.getDayNumber())
                .cityId(d.getCityId())
                .cityName(d.getCityName())
                .destinationName(d.getDestinationName())
                .nights(d.getNights())
                .title(d.getTitle())
                .description(d.getDescription())
                .pricePerPax(d.getPricePerPax())
                .build();
    }

    private QuotationTemplateResponse.HotelItem toHotel(QuotationTemplateHotel h) {
        return QuotationTemplateResponse.HotelItem.builder()
                .id(h.getPublicId())
                .sortOrder(h.getSortOrder())
                .name(h.getName())
                .city(h.getCity())
                .stars(h.getStars())
                .roomType(h.getRoomType())
                .mealPlan(h.getMealPlan())
                .refundable(h.getRefundable())
                .pricePerRoom(h.getPricePerRoom())
                .rooms(h.getRooms())
                .nights(h.getNights())
                .imagePath(h.getImagePath())
                .build();
    }

    // ── Entity → scoring projection ───────────────────────────────────────────

    /** Flattens the aggregate into the immutable record the scorer consumes. */
    public TemplateProfile toProfile(QuotationTemplate t) {
        List<CityRef> cities = t.getItinerary().stream()
                .map(d -> CityRef.of(d.getCityId(), d.getCityName()))
                .filter(c -> !c.isBlank())
                .toList();
        return TemplateProfile.builder()
                .id(t.getId())
                .publicId(t.getPublicId())
                .name(t.getName())
                .cities(cities)
                .nights(t.getDurationNights())
                .hotelTier(t.getHotelTier())
                .basePrice(t.getBasePrice())
                .seasonMonths(t.getSeasonMonths())
                .build();
    }

    // ── Entity + score → match card ───────────────────────────────────────────

    public TemplateMatchResponse toMatchResponse(QuotationTemplate t, MatchScore score) {
        List<TemplateMatchResponse.Component> components = score.components().stream()
                .map(c -> TemplateMatchResponse.Component.builder()
                        .key(c.key())
                        .label(c.label())
                        .weight(c.weight())
                        .applicable(c.applicable())
                        .score(c.scorePercent())
                        .detail(c.detail())
                        .build())
                .toList();

        return TemplateMatchResponse.builder()
                .id(t.getPublicId())
                .name(t.getName())
                .description(t.getDescription())
                .coverImageUrl(t.getCoverImageUrl())
                .durationNights(t.getDurationNights())
                .durationDays(t.getDurationDays())
                .hotelTier(t.getHotelTier())
                .basePrice(t.getBasePrice())
                .cities(cityNames(t))
                .seasonMonths(sortedMonths(t.getSeasonMonths()))
                .matchPercentage(score.percentage())
                .components(components)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Distinct city names in itinerary order, de-duplicated case-insensitively. */
    private List<String> cityNames(QuotationTemplate t) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();
        for (QuotationTemplateItinerary day : t.getItinerary()) {
            String name = day.getCityName();
            if (!StringUtils.hasText(name)) continue;
            if (seen.add(name.trim().toLowerCase(Locale.ROOT))) {
                ordered.add(name.trim());
            }
        }
        return List.copyOf(ordered);
    }

    private List<Integer> sortedMonths(Set<Integer> months) {
        return months == null || months.isEmpty() ? List.of() : List.copyOf(new TreeSet<>(months));
    }
}