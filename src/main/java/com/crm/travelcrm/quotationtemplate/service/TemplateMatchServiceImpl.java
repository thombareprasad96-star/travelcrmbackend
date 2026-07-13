package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchRequest;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchResponse;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.mapper.QuotationTemplateMapper;
import com.crm.travelcrm.quotationtemplate.matching.CityRef;
import com.crm.travelcrm.quotationtemplate.matching.MatchInput;
import com.crm.travelcrm.quotationtemplate.matching.RankedTemplate;
import com.crm.travelcrm.quotationtemplate.matching.TemplateProfile;
import com.crm.travelcrm.quotationtemplate.matching.TemplateScorer;
import com.crm.travelcrm.quotationtemplate.repository.QuotationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a lead into a {@link MatchInput}, hands it to the pure {@link TemplateScorer}, and dresses
 * the ranked result back up as API cards.
 *
 * <p>The interesting work is the city bridge. A lead's itinerary stores {@code destination} and
 * {@code city} as free text, while a template stores real {@code City} ids — so scoring by identity
 * requires resolving those strings first. Two lookups per distinct leg, memoised: the
 * destination-qualified finder, then a name-only fallback for the common case where an agent left
 * the destination blank or spelled it differently. A name that resolves to nothing is still carried
 * into the scorer (as an id-less {@link CityRef}), where it can only match a template city by name —
 * which is the honest outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateMatchServiceImpl implements TemplateMatchService {

    private static final int DEFAULT_LIMIT = 10;

    private final QuotationTemplateRepository templateRepository;
    private final QuotationTemplateMapper mapper;
    private final TemplateScorer scorer;
    private final CityRepository cityRepository;
    private final LeadAccessGuard leadAccessGuard;

    @Override
    @Transactional(readOnly = true)
    public List<TemplateMatchResponse> match(TemplateMatchRequest request) {
        Long tenantId = currentTenantId();
        Lead lead = leadAccessGuard.requireVisible(request.getLeadId(), "LEAD_READ");

        List<QuotationTemplate> candidates =
                templateRepository.findAllByTenantIdAndActiveTrueAndDeletedAtIsNull(tenantId);
        if (candidates.isEmpty()) {
            log.debug("match() | tenantId={} | no active templates", tenantId);
            return List.of();
        }

        MatchInput input = buildInput(lead, request, tenantId);

        Map<Long, QuotationTemplate> byId = new HashMap<>(candidates.size());
        List<TemplateProfile> profiles = new ArrayList<>(candidates.size());
        for (QuotationTemplate template : candidates) {
            byId.put(template.getId(), template);
            profiles.add(mapper.toProfile(template));
        }

        List<RankedTemplate> ranked = scorer.rank(input, profiles);
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();

        List<TemplateMatchResponse> results = ranked.stream()
                .limit(limit)
                .map(r -> mapper.toMatchResponse(byId.get(r.template().id()), r.score()))
                .toList();

        log.debug("match() | lead={} | {} candidate(s) -> {} above threshold -> {} returned",
                request.getLeadId(), candidates.size(), ranked.size(), results.size());
        return results;
    }

    // ── Lead → MatchInput ─────────────────────────────────────────────────────

    private MatchInput buildInput(Lead lead, TemplateMatchRequest request, Long tenantId) {
        List<CityRef> cities = resolveLeadCities(lead, tenantId);

        Integer nights = request.getDurationNights() != null
                ? request.getDurationNights()
                : sumNights(lead.getItinerary());

        BigDecimal budget = request.getBudget() != null ? request.getBudget() : lead.getBudget();

        Integer travelMonth = request.getTravelMonth() != null
                ? request.getTravelMonth()
                : (lead.getTravelDate() != null ? lead.getTravelDate().getMonthValue() : null);

        return MatchInput.builder()
                .cities(cities)
                .nights(nights)
                .hotelTier(request.getHotelTier())   // never on the Lead; only ever from the panel
                .budget(budget)
                .travelMonth(travelMonth)
                .build();
    }

    /** Lead legs in itinerary order, each resolved to a master city id where one exists. */
    private List<CityRef> resolveLeadCities(Lead lead, Long tenantId) {
        List<LeadItinerary> legs = new ArrayList<>(lead.getItinerary());
        // The Lead module puts no @OrderBy on the collection, so sort it ourselves. dayNumber is
        // nullable; fall back to insertion id so the order is at least deterministic.
        legs.sort(Comparator
                .comparing(LeadItinerary::getDayNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(LeadItinerary::getId));

        Map<String, Optional<City>> cache = new HashMap<>();
        List<CityRef> cities = new ArrayList<>(legs.size());
        for (LeadItinerary leg : legs) {
            if (!StringUtils.hasText(leg.getCity())) continue;
            City city = lookupCity(leg.getDestination(), leg.getCity(), tenantId, cache);
            Long cityId = city != null ? Long.valueOf(city.getId()) : null;
            cities.add(CityRef.of(cityId, leg.getCity()));
        }
        return cities;
    }

    private City lookupCity(String destination, String cityName, Long tenantId,
                            Map<String, Optional<City>> cache) {
        String key = norm(destination) + "|" + norm(cityName);
        return cache.computeIfAbsent(key, ignored -> {
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

    private static Integer sumNights(List<LeadItinerary> legs) {
        if (legs == null || legs.isEmpty()) return null;
        int total = 0;
        for (LeadItinerary leg : legs) {
            if (leg.getNights() != null && leg.getNights() > 0) total += leg.getNights();
        }
        return total > 0 ? total : null;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId claim.");
        }
        return tenantId;
    }
}