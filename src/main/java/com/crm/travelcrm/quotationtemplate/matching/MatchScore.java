package com.crm.travelcrm.quotationtemplate.matching;

import java.util.List;

/**
 * The result of scoring one template against one lead: the headline percentage plus every
 * component that produced it, applicable or not.
 *
 * @param percentage 0–100, renormalized over the applicable components only
 */
public record MatchScore(int percentage, List<ComponentScore> components) {

    public MatchScore {
        components = components == null ? List.of() : List.copyOf(components);
    }

    /** How many dimensions we could actually score. Used as the first tie-break when ranking. */
    public long applicableCount() {
        return components.stream().filter(ComponentScore::applicable).count();
    }
}