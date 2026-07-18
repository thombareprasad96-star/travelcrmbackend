package com.crm.travelcrm.quotation.enums;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The six priceable SECTIONS of a quotation, and the bridge between a lead's chosen services
 * (free-text ids the builder's service picker writes) and the section machinery that decides what
 * the PDF and the public weblink render.
 *
 * <p><b>Declaration order IS the canonical section order</b> — flight, hotel, sightseeing, cruise,
 * vehicle, add-ons. One contract shared by three layers (builder tabs, PDF templates, weblink), so
 * a quotation reads the same everywhere. Reordering these constants reorders every rendered document.
 *
 * <p><b>ADDON has no {@code leadServiceId}.</b> No lead service maps to it — add-ons are chosen
 * inside the quotation, never on the lead — so it is permanently exempt from lead-driven gating and
 * its tab stays enabled. It still participates in ordering and in the emptiness rule.
 *
 * <p>The lead vocabulary is deliberately OPEN: {@code visa}, {@code insurance} and {@code passport}
 * are real service ids that map to no section, and an integration may invent more. Unknown ids are
 * silently ignored rather than rejected — a lead must never fail to produce a quotation because it
 * carries a service this enum has not heard of.
 *
 * <p>Plain enum, zero Spring dependencies: {@code QuotationMapper} is a hand-written
 * {@code @Component} and {@code QuotationPdfService} calls this from the public share-link path,
 * where there is no TenantContext and no injected collaborators to lean on.
 */
public enum QuotationSection {

    FLIGHT("flight", "flight"),
    HOTEL("hotel", "hotel"),
    SIGHTSEEING("sightseeing", "sightseeing"),
    CRUISE("cruise", "cruise"),
    VEHICLE("vehicle", "vehicle"),
    /** No lead service maps here — see the class javadoc. Template key is plural ("addons"). */
    ADDON(null, "addons");

    private final String leadServiceId;
    private final String key;

    QuotationSection(String leadServiceId, String key) {
        this.leadServiceId = leadServiceId;
        this.key = key;
    }

    /** The lead-side service id this section is chosen by, or null for {@link #ADDON}. */
    public String leadServiceId() {
        return leadServiceId;
    }

    /** The key the PDF templates and the weblink match on ({@code sectionOrder} entries). */
    public String key() {
        return key;
    }

    /**
     * Resolves one lead service id to its section. Trims and lower-cases first because the ids come
     * from the frontend picker as free text. An id that maps to nothing (visa/insurance/passport, or
     * anything an integration invents) returns empty — never an exception.
     */
    public static Optional<QuotationSection> fromLeadService(String leadService) {
        if (leadService == null) return Optional.empty();
        String id = leadService.trim().toLowerCase();
        if (id.isEmpty()) return Optional.empty();
        for (QuotationSection s : values()) {
            if (id.equals(s.leadServiceId)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Normalizes a lead's raw services list into the set of section keys it selected: known ids only,
     * de-duplicated, and in the order the lead listed them (the builder shows lead-chosen services
     * first, so the caller's order is meaningful and must survive).
     *
     * <p>A null/empty input yields an empty list, which every consumer reads as "no lead information"
     * and FAILS OPEN — everything allowed. That is deliberate: ingested leads routinely carry no
     * services at all, and such a lead must not produce an empty quotation.
     */
    public static List<String> normalize(List<String> leadServices) {
        if (leadServices == null || leadServices.isEmpty()) return new ArrayList<>();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String raw : leadServices) {
            fromLeadService(raw).ifPresent(s -> keys.add(s.key()));
        }
        return new ArrayList<>(keys);
    }
}
