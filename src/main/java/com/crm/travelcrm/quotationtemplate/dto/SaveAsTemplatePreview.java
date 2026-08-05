package com.crm.travelcrm.quotationtemplate.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What "Save as Template" would capture from a quotation, computed before anything is written.
 *
 * <p>This exists so the modal cannot drift from reality. A quotation holds four sections a template
 * has no table for, and its cities have to be re-resolved from free text — telling the agent that
 * afterwards would be a bug report; telling them in the dialog is a feature. Every derived value
 * here is the same one {@code POST /from-quotation} will use if the agent changes nothing.
 */
@Data
@Builder
public class SaveAsTemplatePreview {

    private UUID quotationId;

    // ── Derived defaults, all editable in the modal ───────────────────────────
    private String name;
    private String description;
    private Integer durationNights;
    private Integer hotelTier;

    /** The quotation's grand total. Priced for {@link #pricedForPax} — say so next to the field. */
    private BigDecimal basePrice;

    /** Adults + children the {@link #basePrice} was quoted for; null when the quotation has none. */
    private Integer pricedForPax;

    /** Cities in itinerary order, as they will be stored. */
    private List<City> cities;

    /** Section keys the package will declare — the new services dimension. */
    private List<String> services;

    // ── Honesty ───────────────────────────────────────────────────────────────

    /** Human labels of what comes across, e.g. "Hotels", "Day-wise itinerary". */
    private List<String> capturedSections;

    /**
     * Human labels of what the template cannot hold — flights, cruise, vehicles, add-ons, the
     * policy texts and the pricing adjusters. Shown before the agent commits.
     */
    private List<String> droppedSections;

    /** The closest existing template, when one scores at or above the duplicate threshold. */
    private NearDuplicate nearDuplicate;

    @Data
    @Builder
    public static class City {
        private String name;
        /**
         * True when the name resolved to a master City row. Unresolved cities still work — they
         * match other templates by name — but they cannot earn same-destination near-miss credit,
         * so the modal flags them.
         */
        private boolean resolved;
    }

    @Data
    @Builder
    public static class NearDuplicate {
        private UUID id;
        private String name;
        private int matchPercentage;
    }
}
