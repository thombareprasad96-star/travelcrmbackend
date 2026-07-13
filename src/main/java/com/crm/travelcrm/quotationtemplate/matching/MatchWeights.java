package com.crm.travelcrm.quotationtemplate.matching;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** Templates scoring below this percentage are not returned at all. */
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
}