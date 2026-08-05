package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityGeoRef;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.quotationtemplate.matching.CityRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bridge between free-typed geography and the master {@code City} table.
 *
 * <p>Both things this module matches on store their cities as prose. A lead's itinerary leg is
 * {@code {destination, city}} free text. A {@code Quotation} is worse — it keeps no geography at all
 * beyond {@code QuotationHotel.city} and a comma-joined {@code destination} string, and not one of
 * its child rows carries a master foreign key. Scoring by identity means resolving those strings
 * first, and that resolution is now needed in two places (ranking a lead, and capturing a quotation
 * as a template), so it lives here rather than being copied.
 *
 * <p>Two lookups per distinct leg, memoised: the destination-qualified finder, then a name-only
 * fallback for the common case where the destination was left blank or spelled differently. A name
 * that resolves to nothing is still carried into the scorer as an id-less {@link CityRef}, where it
 * can only match by name — which is the honest outcome, not a failure.
 *
 * <p>{@link #geoIndex} then attaches destination and country in ONE query for every id on both sides
 * of a comparison at once. That is what lets the scorer grade near-misses (a different town in the
 * same destination) without turning the ranking pass into a select per city.
 */
@Component
@RequiredArgsConstructor
public class CityResolver {

    private final CityRepository cityRepository;

    /**
     * A memo for one resolution pass. Callers that resolve several legs should thread the same
     * instance through so a repeated {@code (destination, city)} pair costs one query, not N.
     */
    public static final class Cache {
        private final Map<String, Optional<City>> byKey = new HashMap<>();
        private final Map<Long, Optional<City>> byId = new HashMap<>();
    }

    public Cache newCache() {
        return new Cache();
    }

    // ── Name → id ─────────────────────────────────────────────────────────────

    /**
     * Resolve one free-typed leg. The returned ref always carries the name the user typed, and
     * carries the master id only when one could be found — never a guess.
     */
    public CityRef resolve(String destinationName, String cityName, Long tenantId, Cache cache) {
        return resolve(null, destinationName, cityName, tenantId, cache);
    }

    /**
     * As above, but preferring an id the source document already recorded.
     *
     * <p>{@code LeadItinerary} carries a nullable {@code cityId} alongside its two name strings —
     * stamped when the lead was created through the cascading dropdowns, absent when it arrived from
     * an integration or was typed free-hand. Trusting it when present skips the name lookup entirely
     * and, more importantly, sidesteps its one real failure mode: a city name is unique only per
     * (tenant, country), so the name-only fallback resolves "Springfield" to whichever row is oldest.
     *
     * <p>The hint is <b>validated tenant-owned</b> before use. It is a logical id with no foreign key
     * behind it, so a client is free to send any number at all; an id belonging to another tenant is
     * discarded and the name path runs as though it had never been sent.
     */
    public CityRef resolve(Long cityIdHint, String destinationName, String cityName,
                           Long tenantId, Cache cache) {
        if (!StringUtils.hasText(cityName)) return null;

        if (cityIdHint != null) {
            City hinted = cache.byId
                    .computeIfAbsent(cityIdHint, id -> cityRepository.findByIdAndTenantId(id, tenantId))
                    .orElse(null);
            if (hinted != null) {
                // Keep the name the document spells — that is what the quotation and the PDF print,
                // and it must not shift under an edited master row.
                return CityRef.of(hinted.getId(), cityName.trim());
            }
        }

        City city = lookup(destinationName, cityName, tenantId, cache);
        Long cityId = city != null ? Long.valueOf(city.getId()) : null;
        return CityRef.of(cityId, cityName.trim());
    }

    private City lookup(String destination, String cityName, Long tenantId, Cache cache) {
        String key = norm(destination) + "|" + norm(cityName);
        return cache.byKey.computeIfAbsent(key, ignored -> {
            Optional<City> found = Optional.empty();
            if (StringUtils.hasText(destination)) {
                found = cityRepository.findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(
                        tenantId, destination.trim(), cityName.trim());
            }
            if (found.isEmpty()) {
                found = cityRepository.findFirstByTenantIdAndNameIgnoreCaseOrderByIdAsc(
                        tenantId, cityName.trim());
            }
            return found;
        }).orElse(null);
    }

    // ── id → destination / country ────────────────────────────────────────────

    /**
     * One query for every city id in play. Pass the ids from BOTH sides of the comparison together:
     * near-miss grading needs the requested city and the offered city to be resolvable against the
     * same map, and doing it in two calls would double the round trips for no benefit.
     */
    public Map<Long, CityGeoRef> geoIndex(Collection<Long> cityIds, Long tenantId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (cityIds != null) {
            for (Long id : cityIds) {
                if (id != null) ids.add(id);
            }
        }
        if (ids.isEmpty()) return Map.of();

        Map<Long, CityGeoRef> index = new HashMap<>(ids.size());
        for (CityGeoRef ref : cityRepository.findGeoRefsByTenantIdAndIdIn(tenantId, ids)) {
            index.put(ref.id(), ref);
        }
        return index;
    }

    /** Every non-null id across any number of city lists — the argument for {@link #geoIndex}. */
    @SafeVarargs
    public static Set<Long> idsOf(List<CityRef>... lists) {
        Set<Long> ids = new LinkedHashSet<>();
        for (List<CityRef> list : lists) {
            if (list == null) continue;
            for (CityRef c : list) {
                if (c != null && c.id() != null) ids.add(c.id());
            }
        }
        return ids;
    }

    /**
     * Copy a city list with destination/country filled in from the index. Refs whose id is unknown
     * to the index — an unresolved free-text name, or a city from another tenant — are returned
     * untouched, so they simply earn no near-miss credit.
     */
    public static List<CityRef> withGeography(List<CityRef> cities, Map<Long, CityGeoRef> index) {
        if (cities == null || cities.isEmpty()) return List.of();
        if (index == null || index.isEmpty()) return List.copyOf(cities);

        List<CityRef> out = new ArrayList<>(cities.size());
        for (CityRef c : cities) {
            if (c == null) continue;
            CityGeoRef geo = c.id() == null ? null : index.get(c.id());
            out.add(geo == null
                    ? c
                    : CityRef.of(c.id(), c.name(), geo.destinationId(), geo.countryId()));
        }
        return List.copyOf(out);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
