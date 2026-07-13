package com.crm.travelcrm.quotationtemplate.matching;

import java.util.Locale;

/**
 * A city as either side of the match knows it: a master id when one could be resolved, and always
 * a name to fall back on.
 *
 * <p>A lead stores its itinerary cities as free text, so some of them will never resolve to a
 * master {@code City} row. Those still have to be comparable against a template's cities, which do
 * carry ids — hence {@link #canonicalKey()}: an id-keyed token when we have one, a normalised name
 * otherwise. Two cities match when either token matches, so a resolved "#42" and an unresolved
 * "delhi" both find the template's Delhi.
 */
public record CityRef(Long id, String name) {

    public static CityRef of(Long id, String name) {
        return new CityRef(id, name);
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

    /** A city with neither an id nor a name contributes nothing and is dropped before scoring. */
    public boolean isBlank() {
        return id == null && normalizedName().isEmpty();
    }
}