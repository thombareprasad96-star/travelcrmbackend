package com.crm.travelcrm.quotationtemplate.matching;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * What we know about the customer, distilled to exactly the five things the scorer reads. Built
 * from a {@code Lead} (plus whatever the agent typed into the match panel) by the service layer.
 *
 * <p>Every field except {@link #cities} is nullable, and null means "not applicable" rather than
 * "zero" — the scorer drops the component and renormalizes. That distinction is the whole reason
 * this is a record of boxed types and not a bag of primitives.
 *
 * <p>A record, not an entity, so {@link TemplateScorer} is a pure function that cannot touch the
 * database. Its test passes no repositories at all.
 */
@Builder
public record MatchInput(
        /** The lead's itinerary cities, resolved to master ids where possible. Never null. */
        List<CityRef> cities,

        /** Total nights the customer asked for. */
        Integer nights,

        /** Star tier the agent selected. {@code Lead} has no column for this. */
        Integer hotelTier,

        /** {@code Lead.budget} — the customer's planned spend, same unit as a template's basePrice. */
        BigDecimal budget,

        /** Month of {@code Lead.travelDate}, 1–12. */
        Integer travelMonth,

        /**
         * Services the customer asked for — {@code Lead.services} normalised, or a quotation's
         * {@code allowedServices} snapshot. Never null; <b>empty means "not stated"</b> and makes the
         * dimension inapplicable rather than scoring every package zero.
         */
        List<String> services
) {
    public MatchInput {
        cities = cities == null ? List.of() : List.copyOf(cities);
        services = services == null ? List.of() : List.copyOf(services);
    }
}