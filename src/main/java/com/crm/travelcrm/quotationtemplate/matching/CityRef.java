package com.crm.travelcrm.quotationtemplate.matching;

import java.util.Locale;

/**
 * A city as either side of the match knows it: a master id when one could be resolved, always a name
 * to fall back on, and — when the master row was reachable — the destination and country it sits
 * under.
 *
 * <p>A lead stores its itinerary cities as free text, so some of them will never resolve to a
 * master {@code City} row. Those still have to be comparable against a template's cities, which do
 * carry ids — hence {@link #canonicalKey()}: an id-keyed token when we have one, a normalised name
 * otherwise. Two cities match when either token matches, so a resolved "#42" and an unresolved
 * "delhi" both find the template's Delhi.
 *
 * <p><b>{@link #destinationId} / {@link #countryId} are the near-miss ladder.</b> Exact-city-or-nothing
 * scoring reads a Sikkim package as a near-total miss for a Sikkim trip that happens to name
 * different towns, which is not how an agent thinks about it. Both are nullable and both default to
 * null, so a {@code CityRef} built without them behaves exactly as it did before they existed —
 * every near-miss simply evaluates to "unrelated".
 */
public record CityRef(Long id, String name, Long destinationId, Long countryId) {

    /** Identity-only ref — no geography, so it can never earn near-miss credit. */
    public static CityRef of(Long id, String name) {
        return new CityRef(id, name, null, null);
    }

    /** Fully-resolved ref, as {@code CityResolver} produces from a master {@code City} row. */
    public static CityRef of(Long id, String name, Long destinationId, Long countryId) {
        return new CityRef(id, name, destinationId, countryId);
    }

    /** Lower-cased, whitespace-collapsed name. Empty when there is no usable name. */
    public String normalizedName() {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** {@code "#<id>"} when the city resolved to a master row, else its normalised name. */
    public String canonicalKey() {
        return id != null ? "#" + id : normalizedName();
    }

    /** True when the two refer to the same city by id, or failing that by name. */
    public boolean matches(CityRef other) {
        if (other == null) return false;
        if (id != null && id.equals(other.id())) return true;
        String a = normalizedName();
        return !a.isEmpty() && a.equals(other.normalizedName());
    }

    /**
     * Same destination but a different city — e.g. Pelling against Lachung, both in Sikkim.
     * False when either side never resolved to a master row, and false when they are the same city
     * (that is {@link #matches}, worth full credit, not partial).
     */
    public boolean sameDestinationAs(CityRef other) {
        if (other == null || destinationId == null || other.destinationId() == null) return false;
        return destinationId.equals(other.destinationId()) && !matches(other);
    }

    /** Same country, different destination — the weakest signal that is still a signal. */
    public boolean sameCountryAs(CityRef other) {
        if (other == null || countryId == null || other.countryId() == null) return false;
        return countryId.equals(other.countryId()) && !sameDestinationAs(other) && !matches(other);
    }

    /** A city with neither an id nor a name contributes nothing and is dropped before scoring. */
    public boolean isBlank() {
        return id == null && normalizedName().isEmpty();
    }
}
