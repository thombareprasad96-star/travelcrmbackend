package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.master.geography.repository.CityGeoRef;
import com.crm.travelcrm.quotation.enums.QuotationSection;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchRequest;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchResponse;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.mapper.QuotationTemplateMapper;
import com.crm.travelcrm.quotationtemplate.matching.CityRef;
import com.crm.travelcrm.quotationtemplate.matching.MatchInput;
import com.crm.travelcrm.quotationtemplate.matching.MatchWeights;
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
import java.util.Map;

/**
 * Turns a lead into a {@link MatchInput}, hands it to the pure {@link TemplateScorer}, and dresses
 * the ranked result back up as API cards.
 *
 * <p>The interesting work is the city bridge, which now lives in {@link CityResolver} because
 * "Save as Template" needs exactly the same thing: a lead's itinerary stores {@code destination} and
 * {@code city} as free text, while a template stores real {@code City} ids, so scoring by identity
 * requires resolving those strings first. A name that resolves to nothing is still carried into the
 * scorer (as an id-less {@link CityRef}), where it can only match a template city by name — which is
 * the honest outcome.
 *
 * <p>A second, batched lookup then attaches each city's destination and country so the scorer can
 * grade near-misses. One query for every city on both sides of the whole ranking pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateMatchServiceImpl implements TemplateMatchService {

    private static final int DEFAULT_LIMIT = 10;

    private final QuotationTemplateRepository templateRepository;
    private final QuotationTemplateMapper mapper;
    private final TemplateScorer scorer;
    private final CityResolver cityResolver;
    private final LeadAccessGuard leadAccessGuard;
    private final MatchWeights weights;

    @Override
    @Transactional(readOnly = true)
    public List<TemplateMatchResponse> match(TemplateMatchRequest request) {
        Long tenantId = currentTenantId();
        Lead lead = leadAccessGuard.requireVisible(request.getLeadId(), "LEAD_READ");

        List<QuotationTemplate> candidates = loadCandidates(tenantId);
        if (candidates.isEmpty()) {
            log.debug("match() | tenantId={} | no active templates", tenantId);
            return List.of();
        }

        List<CityRef> leadCities = resolveLeadCities(lead, tenantId);

        Map<Long, QuotationTemplate> byId = new HashMap<>(candidates.size());
        List<TemplateProfile> flat = new ArrayList<>(candidates.size());
        for (QuotationTemplate template : candidates) {
            byId.put(template.getId(), template);
            flat.add(mapper.toProfile(template));
        }

        // One geography lookup for every city in play — the lead's and every candidate's — so the
        // near-miss ladder costs a single extra query for the whole pass rather than one per city.
        Map<Long, CityGeoRef> geo = cityResolver.geoIndex(
                CityResolver.idsOf(leadCities, allCities(flat)), tenantId);

        List<TemplateProfile> profiles = new ArrayList<>(flat.size());
        for (TemplateProfile p : flat) {
            profiles.add(QuotationTemplateServiceImpl.withGeography(p, geo));
        }

        MatchInput input = buildInput(lead, request, CityResolver.withGeography(leadCities, geo));
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();

        List<RankedTemplate> ranked = scorer.rankAll(input, profiles);
        List<RankedTemplate> above = ranked.stream()
                .filter(r -> r.score().percentage() >= weights.getMinScore())
                .limit(limit)
                .toList();

        if (!above.isEmpty()) {
            log.debug("match() | lead={} | {} candidate(s) -> {} above threshold -> {} returned",
                    request.getLeadId(), candidates.size(), ranked.size(), above.size());
            return above.stream()
                    .map(r -> mapper.toMatchResponse(byId.get(r.template().id()), r.score()))
                    .toList();
        }

        // ── Cold start ────────────────────────────────────────────────────────
        // Nothing cleared the bar. An empty panel reads as "this feature is broken"; the best of a
        // bad set, clearly flagged, reads as "nothing fits — here is what you have". Ordered by score
        // rather than by raw popularity even down here: a 38 % really is closer than a 12 %, and the
        // scorer's comparator already breaks those near-ties on how often each package has been used.
        int coldStart = Math.min(weights.getColdStartLimit(), limit);
        if (coldStart <= 0) {
            log.debug("match() | lead={} | nothing above threshold, cold start disabled", request.getLeadId());
            return List.of();
        }
        List<TemplateMatchResponse> fallback = ranked.stream()
                .limit(coldStart)
                .map(r -> mapper.toMatchResponse(byId.get(r.template().id()), r.score(), true))
                .toList();
        log.debug("match() | lead={} | nothing above threshold -> {} cold-start suggestion(s)",
                request.getLeadId(), fallback.size());
        return fallback;
    }

    // ── Candidates ────────────────────────────────────────────────────────────

    /**
     * Every live, active template for the tenant. Scored in memory, deliberately.
     *
     * <p><b>A SQL pre-filter on geography would be incorrect here, not merely premature.</b> Because
     * destination carries only part of the weight and the scorer renormalizes over whichever
     * dimensions applied, a template covering NONE of the requested cities can still reach
     * {@code scorer.maxScoreWithoutDestination()} — 68 % at the shipped weights — which is well above
     * the 40 % threshold. Narrowing the candidate set by city would silently discard templates that
     * were going to be shown. Such a pre-filter is only sound once
     * {@code MatchWeights.minScore > scorer.maxScoreWithoutDestination()}; check that inequality
     * before adding one.
     *
     * <p>The load itself is not an N+1: every collection on {@code QuotationTemplate} carries
     * {@code @BatchSize(50)}, so the itinerary, season months and services of N templates cost a
     * handful of selects in total, not N.
     */
    private List<QuotationTemplate> loadCandidates(Long tenantId) {
        return templateRepository.findAllByTenantIdAndActiveTrueAndDeletedAtIsNull(tenantId);
    }

    // ── Lead → MatchInput ─────────────────────────────────────────────────────

    private MatchInput buildInput(Lead lead, TemplateMatchRequest request, List<CityRef> cities) {
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
                // Normalized to the section vocabulary, because that is all a package can express.
                // visa/insurance/passport drop out here rather than counting against every template.
                .services(QuotationSection.normalize(lead.getServices()))
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

        CityResolver.Cache cache = cityResolver.newCache();
        List<CityRef> cities = new ArrayList<>(legs.size());
        for (LeadItinerary leg : legs) {
            if (!StringUtils.hasText(leg.getCity())) continue;
            // The stored cityId, when the lead was built through the cascading dropdowns, beats
            // re-deriving it from the name — a city name is unique only per (tenant, country).
            CityRef ref = cityResolver.resolve(
                    leg.getCityId(), leg.getDestination(), leg.getCity(), tenantId, cache);
            if (ref != null) cities.add(ref);
        }
        return cities;
    }

    private static List<CityRef> allCities(List<TemplateProfile> profiles) {
        List<CityRef> out = new ArrayList<>();
        for (TemplateProfile p : profiles) out.addAll(p.cities());
        return out;
    }

    private static Integer sumNights(List<LeadItinerary> legs) {
        if (legs == null || legs.isEmpty()) return null;
        int total = 0;
        for (LeadItinerary leg : legs) {
            if (leg.getNights() != null && leg.getNights() > 0) total += leg.getNights();
        }
        return total > 0 ? total : null;
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
