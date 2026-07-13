package com.crm.travelcrm.quotationtemplate.matching;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The scoreable projection of a {@code QuotationTemplate}. Flattened off the entity before ranking
 * so {@link TemplateScorer} never sees a managed object (and so a lazy collection can never be
 * initialised from inside the scoring loop).
 *
 * @param seasonMonths months 1–12 the package is sold in; <b>empty means year-round</b>
 */
@Builder
public record TemplateProfile(
        long id,
        UUID publicId,
        String name,
        List<CityRef> cities,
        Integer nights,
        Integer hotelTier,
        BigDecimal basePrice,
        Set<Integer> seasonMonths
) {
    public TemplateProfile {
        cities = cities == null ? List.of() : List.copyOf(cities);
        seasonMonths = seasonMonths == null ? Set.of() : Set.copyOf(seasonMonths);
    }
}