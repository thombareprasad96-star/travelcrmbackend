package com.crm.travelcrm.quotationtemplate.matching;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tunable weights for {@link TemplateScorer}, bound from {@code crm.quotation-template.matching.*}
 * (relaxed binding, so {@code hotel-tier} sets {@link #hotelTier}).
 *
 * <p>The weights need not sum to 1: the scorer renormalizes over whichever components actually
 * applied to a given lead, so a lead with no budget on file is scored purely on the rest rather
 * than being silently docked 20 points.
 */
@Component
@ConfigurationProperties(prefix = "crm.quotation-template.matching")
@Getter
@Setter
public class MatchWeights {

    /** Do the template's cities cover the ones the customer asked for? */
    private double destination = 0.35;

    /** Does the package run for about as many nights as the lead's itinerary? */
    private double duration = 0.20;

    /** Star tier — the only dimension a Lead has no column for; the agent supplies it. */
    private double hotelTier = 0.15;

    /** Indicative package price against {@code Lead.budget}. */
    private double budget = 0.20;

    /** Is the lead travelling in a month this package is sold in? */
    private double season = 0.10;

    /**
     * Does the package cover the services the customer asked for (flights, hotel, transfers …)?
     * Weighted below destination on purpose: a missing service is usually addable inside the
     * quotation, whereas a package that goes to the wrong place is not salvageable.
     */
    private double services = 0.10;

    /** Templates scoring below this percentage are not returned as matches. */
    private int minScore = 40;

    /**
     * How hard a template priced <i>under</i> the lead's budget is penalised, per unit of relative
     * difference. Cheap is mildly suspicious (under-specced), never disqualifying.
     */
    private double underBudgetPenalty = 0.5;

    /** How hard an <i>over</i>-budget template is penalised. 3× the under-budget slope: 67 % over ⇒ 0. */
    private double overBudgetPenalty = 1.5;

    /**
     * Months of slack before an out-of-season template scores zero. At the default 3, travelling one
     * month outside the window still scores 0.67.
     */
    private double seasonToleranceMonths = 3.0;

    // ── Geography near-miss ladder ────────────────────────────────────────────

    /**
     * Credit for a template city in the SAME destination as a requested one but not the same city —
     * Lachung standing in for Pelling, both in Sikkim. Half marks: the region is right, the stop is
     * not, and swapping one town inside a package is a small edit.
     *
     * <p>Set to 0 to restore the old exact-city-or-nothing behaviour.
     */
    private double nearMissDestination = 0.5;

    /**
     * Credit for a template city in the same COUNTRY but a different destination. Deliberately low —
     * "somewhere else in India" is barely a signal, but it is not nothing when the alternative is an
     * empty suggestion list.
     */
    private double nearMissCountry = 0.2;

    // ── Services ──────────────────────────────────────────────────────────────

    /**
     * How much each requested service counts toward the services score. A package that omits the
     * flights is far more wrong than one that omits the visa assistance, and an unweighted set
     * overlap cannot express that.
     *
     * <p>Keyed by the lowercase {@code QuotationSection} key or lead service id. A service not listed
     * here falls back to {@link #defaultServiceImportance}, so an integration inventing a new service
     * id degrades quietly instead of scoring zero. Spring MERGES map entries rather than replacing
     * the map, so overriding one key in properties leaves the rest of these defaults intact.
     */
    private Map<String, Integer> serviceImportance = new LinkedHashMap<>(Map.of(
            "hotel", 3,
            "flight", 3,
            "sightseeing", 2,
            "vehicle", 2,
            "cruise", 2,
            "addons", 1,
            "visa", 1,
            "insurance", 1,
            "passport", 1));

    /** Importance for a service id nobody has declared. */
    private int defaultServiceImportance = 1;

    // ── Ranking behaviour ─────────────────────────────────────────────────────

    /**
     * At or above this percentage, saving a new template warns that a near-identical one already
     * exists. Uses the same scorer, run with the quotation being saved as the "lead" side — a
     * duplicate is just a template that matches itself almost perfectly.
     */
    private int duplicateWarnScore = 85;

    /**
     * How many below-threshold templates to offer when NOTHING clears {@link #minScore}. An empty
     * suggestion panel teaches the agent the feature is broken; a short "no strong matches, here are
     * your most-used packages" list does not. Set to 0 to disable the fallback.
     */
    private int coldStartLimit = 3;
}
