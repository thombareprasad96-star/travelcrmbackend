package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.DuplicateResourceException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.CityGeoRef;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.enums.QuotationSection;
import com.crm.travelcrm.quotation.mapper.QuotationMapper;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.quotation.service.QuotationService;
import com.crm.travelcrm.quotationtemplate.dto.ApplyTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateResponse;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplatePreview;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplateRequest;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateHotel;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateItinerary;
import com.crm.travelcrm.quotationtemplate.mapper.QuotationTemplateMapper;
import com.crm.travelcrm.quotationtemplate.matching.CityRef;
import com.crm.travelcrm.quotationtemplate.matching.MatchInput;
import com.crm.travelcrm.quotationtemplate.matching.MatchWeights;
import com.crm.travelcrm.quotationtemplate.matching.RankedTemplate;
import com.crm.travelcrm.quotationtemplate.matching.TemplateProfile;
import com.crm.travelcrm.quotationtemplate.matching.TemplateScorer;
import com.crm.travelcrm.quotationtemplate.repository.QuotationTemplateRepository;
import com.crm.travelcrm.quotationtemplate.specification.QuotationTemplateSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationTemplateServiceImpl implements QuotationTemplateService {

    /** Whitelist: an arbitrary sortBy from the query string would blow up on an unknown property. */
    private static final Set<String> SORTABLE =
            Set.of("name", "createdAt", "updatedAt", "basePrice", "durationNights", "hotelTier");

    private final QuotationTemplateRepository templateRepository;
    private final QuotationTemplateMapper mapper;
    private final CityRepository cityRepository;
    private final LeadAccessGuard leadAccessGuard;
    private final TemplateQuotationAssembler assembler;
    private final QuotationService quotationService;

    // ── Save-as-Template collaborators ────────────────────────────────────────
    private final QuotationRepository quotationRepository;
    private final QuotationMapper quotationMapper;
    private final SubAgentScope subAgentScope;
    private final QuotationTemplateExtractor extractor;
    private final CityResolver cityResolver;
    private final TemplateScorer scorer;
    private final MatchWeights weights;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotationTemplateResponse create(QuotationTemplateRequest request) {
        return persist(request, null, null, currentTenantId());
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotationTemplateResponse update(UUID publicId, QuotationTemplateRequest request) {
        return persist(request, publicId, null, currentTenantId());
    }

    /**
     * The one write path. Create, update and both save-as-template variants funnel through here so
     * the duplicate-name rule, the child rebuild and the provenance stamp can never drift between
     * them.
     *
     * @param updateTemplateId          null to create, otherwise the template to overwrite
     * @param sourceQuotationPublicId   set only when capturing a quotation; null NEVER clears an
     *                                  existing link, so an ordinary hand edit keeps its provenance
     */
    private QuotationTemplateResponse persist(QuotationTemplateRequest request,
                                              UUID updateTemplateId,
                                              UUID sourceQuotationPublicId,
                                              Long tenantId) {
        String name = request.getName().trim();
        QuotationTemplate template;

        if (updateTemplateId != null) {
            template = loadOwned(updateTemplateId, tenantId);
            if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
                    tenantId, name, template.getId())) {
                throw new DuplicateResourceException("A template named '" + name + "' already exists");
            }
        } else {
            if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)) {
                throw new DuplicateResourceException("A template named '" + name + "' already exists");
            }
            template = new QuotationTemplate();
            template.setTenantId(tenantId);
        }

        applyRequest(request, template, tenantId);
        if (sourceQuotationPublicId != null) {
            template.setSourceQuotationPublicId(sourceQuotationPublicId);
        }

        QuotationTemplate saved = templateRepository.save(template);
        log.info("Quotation template {} | publicId: {} | tenantId: {} | fromQuotation: {}",
                updateTemplateId != null ? "updated" : "created",
                saved.getPublicId(), tenantId, sourceQuotationPublicId);
        return mapper.toResponse(saved);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public QuotationTemplateResponse getByPublicId(UUID publicId) {
        return mapper.toResponse(loadOwned(publicId, currentTenantId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuotationTemplateResponse> search(String keyword, Boolean active,
                                                  int page, int size, String sortBy, String sortDir) {
        Long tenantId = currentTenantId();
        String sortProperty = SORTABLE.contains(sortBy) ? sortBy : "createdAt";
        Sort sort = "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(sortProperty).ascending()
                : Sort.by(sortProperty).descending();

        Specification<QuotationTemplate> spec = QuotationTemplateSpecification.base(tenantId)
                .and(QuotationTemplateSpecification.search(keyword))
                .and(QuotationTemplateSpecification.active(active));

        return templateRepository.findAll(spec, PageRequest.of(page, size, sort))
                .map(mapper::toResponse);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        QuotationTemplate template = loadOwned(publicId, currentTenantId());
        template.softDelete(currentUserEmail());
        templateRepository.save(template);
        log.info("Quotation template soft-deleted | publicId: {}", publicId);
    }

    // ── Apply (clone → edit) ──────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotationResponseDto apply(UUID templatePublicId, ApplyTemplateRequest request) {
        QuotationTemplate template = loadOwned(templatePublicId, currentTenantId());
        if (Boolean.FALSE.equals(template.getActive())) {
            throw new BusinessException(
                    "Template '" + template.getName() + "' is archived and cannot be applied",
                    HttpStatus.CONFLICT);
        }

        // Resolve through the guard so an agent cannot seed a quotation from a lead they can't see.
        // QuotationService.create re-resolves it; one extra read, and that module stays authoritative.
        Lead lead = leadAccessGuard.requireVisible(request.getLeadId(), "LEAD_READ");

        QuotationRequestDto payload = assembler.toQuotationRequest(template, lead, request.getTitle());
        // Both halves of the provenance link are written here: forward on the quotation, and the
        // usage counter on the template that feeds the ranking tie-break.
        payload.setSourceTemplatePublicId(template.getPublicId());
        QuotationResponseDto quotation = quotationService.create(payload);

        template.recordApplied(LocalDateTime.now());
        templateRepository.save(template);

        log.info("Template {} applied to lead {} -> quotation {} | timesApplied now {}",
                templatePublicId, request.getLeadId(), quotation.getPublicId(), template.getTimesApplied());
        return quotation;
    }

    // ── Save as Template (quotation → template) ───────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SaveAsTemplatePreview previewFromQuotation(UUID quotationPublicId) {
        Long tenantId = currentTenantId();
        Captured captured = capture(quotationPublicId, tenantId);
        QuotationTemplateRequest req = captured.capture().request();

        return SaveAsTemplatePreview.builder()
                .quotationId(quotationPublicId)
                .name(req.getName())
                .description(req.getDescription())
                .durationNights(req.getDurationNights())
                .hotelTier(req.getHotelTier())
                .basePrice(req.getBasePrice())
                .pricedForPax(pricedForPax(captured.quotation()))
                .cities(captured.cities().stream()
                        .map(c -> SaveAsTemplatePreview.City.builder()
                                .name(c.name())
                                .resolved(c.id() != null)
                                .build())
                        .toList())
                .services(req.getServices())
                .capturedSections(captured.capture().capturedSections())
                .droppedSections(captured.capture().droppedSections())
                .nearDuplicate(findNearDuplicate(captured, null, tenantId))
                .build();
    }

    @Override
    @Transactional
    public QuotationTemplateResponse saveFromQuotation(SaveAsTemplateRequest request) {
        Long tenantId = currentTenantId();
        Captured captured = capture(request.getQuotationId(), tenantId);
        QuotationTemplateRequest payload = captured.capture().request();

        // The agent's edits win over everything we derived; a null field means "keep the derivation",
        // which is why every override on the request is boxed.
        if (StringUtils.hasText(request.getName())) payload.setName(request.getName().trim());
        if (request.getDescription() != null) payload.setDescription(request.getDescription());
        if (request.getActive() != null) payload.setActive(request.getActive());
        if (request.getHotelTier() != null) payload.setHotelTier(request.getHotelTier());
        if (request.getBasePrice() != null) payload.setBasePrice(request.getBasePrice());
        if (request.getSeasonMonths() != null) payload.setSeasonMonths(request.getSeasonMonths());

        return persist(payload, request.getUpdateTemplateId(), request.getQuotationId(), tenantId);
    }

    /** A quotation, read under the caller's row scope, plus everything derived from it. */
    private record Captured(Quotation quotation,
                            QuotationTemplateExtractor.Capture capture,
                            List<CityRef> cities) {}

    /**
     * Read the quotation, recover its geography, and hand the whole lot to the extractor.
     *
     * <p>The quotation is loaded exactly the way {@code QuotationServiceImpl} loads it — tenant-scoped
     * finder plus the sub-agent row check — rather than through {@code QuotationService}, which
     * exposes only DTOs and would strip the child rows this needs.
     */
    private Captured capture(UUID quotationPublicId, Long tenantId) {
        Quotation q = quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(quotationPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + quotationPublicId));
        subAgentScope.assertVisible(q, quotationPublicId);

        Lead lead = visibleLeadOrNull(q);
        List<QuotationTemplateExtractor.RawLeg> raw = QuotationTemplateExtractor.legsOf(q, lead);

        CityResolver.Cache cache = cityResolver.newCache();
        List<QuotationTemplateExtractor.ResolvedLeg> legs = new ArrayList<>(raw.size());
        List<CityRef> cities = new ArrayList<>(raw.size());
        for (QuotationTemplateExtractor.RawLeg leg : raw) {
            CityRef ref = cityResolver.resolve(
                    leg.cityIdHint(), leg.destinationName(), leg.cityName(), tenantId, cache);
            if (ref == null) continue;
            legs.add(new QuotationTemplateExtractor.ResolvedLeg(
                    ref.id(), ref.name(), leg.destinationName(), leg.nights()));
            cities.add(ref);
        }

        BigDecimal grandTotal = quotationMapper.computeTotals(q).getGrandTotal();
        return new Captured(q, extractor.extract(q, legs, grandTotal), List.copyOf(cities));
    }

    /**
     * The quotation's lead, when the caller may see it. A lead that has been deleted, or reassigned
     * out of this caller's row scope, must not fail the capture — its itinerary is only ever an
     * enrichment over the geography the quotation already carries in its hotel rows.
     */
    private Lead visibleLeadOrNull(Quotation q) {
        if (q.getLeadPublicId() == null) return null;
        try {
            return leadAccessGuard.requireVisible(q.getLeadPublicId(), "LEAD_READ");
        } catch (RuntimeException ex) {
            log.debug("capture() | quotation {} | lead {} not visible ({}), falling back to hotel cities",
                    q.getPublicId(), q.getLeadPublicId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * "You already have this package." The duplicate check is the ordinary matcher run with the
     * quotation itself on the lead side — a duplicate is, precisely, a template that scores near
     * 100 against the thing being saved. No second similarity implementation to keep in step.
     */
    private SaveAsTemplatePreview.NearDuplicate findNearDuplicate(Captured captured,
                                                                  UUID excludeTemplateId,
                                                                  Long tenantId) {
        List<QuotationTemplate> candidates =
                templateRepository.findAllByTenantIdAndActiveTrueAndDeletedAtIsNull(tenantId);
        if (candidates.isEmpty()) return null;

        QuotationTemplateRequest req = captured.capture().request();
        List<TemplateProfile> flat = candidates.stream()
                .filter(t -> excludeTemplateId == null || !excludeTemplateId.equals(t.getPublicId()))
                // A template captured from THIS quotation is not a duplicate of it — it is it.
                .filter(t -> !captured.quotation().getPublicId().equals(t.getSourceQuotationPublicId()))
                .map(mapper::toProfile)
                .toList();
        if (flat.isEmpty()) return null;

        Map<Long, CityGeoRef> geo = cityResolver.geoIndex(
                CityResolver.idsOf(captured.cities(), allCities(flat)), tenantId);

        MatchInput self = MatchInput.builder()
                .cities(CityResolver.withGeography(captured.cities(), geo))
                .nights(req.getDurationNights())
                .hotelTier(req.getHotelTier())
                .budget(req.getBasePrice())
                .travelMonth(captured.quotation().getTravelDate() == null
                        ? null : captured.quotation().getTravelDate().getMonthValue())
                .services(req.getServices())
                .build();

        List<TemplateProfile> profiles = flat.stream()
                .map(p -> withGeography(p, geo))
                .toList();

        List<RankedTemplate> ranked = scorer.rankAll(self, profiles);
        if (ranked.isEmpty()) return null;

        RankedTemplate best = ranked.getFirst();
        if (best.score().percentage() < weights.getDuplicateWarnScore()) return null;

        return SaveAsTemplatePreview.NearDuplicate.builder()
                .id(best.template().publicId())
                .name(best.template().name())
                .matchPercentage(best.score().percentage())
                .build();
    }

    private static List<CityRef> allCities(List<TemplateProfile> profiles) {
        List<CityRef> out = new ArrayList<>();
        for (TemplateProfile p : profiles) out.addAll(p.cities());
        return out;
    }

    static TemplateProfile withGeography(TemplateProfile p, Map<Long, CityGeoRef> geo) {
        return TemplateProfile.builder()
                .id(p.id()).publicId(p.publicId()).name(p.name())
                .cities(CityResolver.withGeography(p.cities(), geo))
                .nights(p.nights()).hotelTier(p.hotelTier()).basePrice(p.basePrice())
                .seasonMonths(p.seasonMonths()).services(p.services()).timesApplied(p.timesApplied())
                .build();
    }

    /** Adults + children the quotation's grand total was priced for; null when it carries neither. */
    private static Integer pricedForPax(Quotation q) {
        int pax = (q.getAdults() == null ? 0 : q.getAdults())
                + (q.getChildren() == null ? 0 : q.getChildren());
        return pax > 0 ? pax : null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ════════════════════════════════════════════════════════════════════════

    private QuotationTemplate loadOwned(UUID publicId, Long tenantId) {
        return templateRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation template not found: " + publicId));
    }

    /** Rebuilds the whole aggregate from the request. Children are replaced, never merged. */
    private void applyRequest(QuotationTemplateRequest request, QuotationTemplate template, Long tenantId) {
        template.setName(request.getName().trim());
        template.setDescription(request.getDescription());
        template.setCoverImageUrl(request.getCoverImageUrl());
        template.setActive(request.getActive() == null || request.getActive());
        template.setHotelTier(request.getHotelTier());
        template.setBasePrice(request.getBasePrice());

        template.getSeasonMonths().clear();
        if (request.getSeasonMonths() != null) {
            request.getSeasonMonths().stream().filter(Objects::nonNull).forEach(template.getSeasonMonths()::add);
        }

        applyItinerary(request, template, tenantId);
        applyHotels(request, template);
        applyServices(request, template);

        replaceAll(template.getInclusions(), request.getInclusions());
        replaceAll(template.getExclusions(), request.getExclusions());

        template.setDurationNights(resolveDurationNights(request, template));
    }

    /**
     * Services are normalized to the {@code QuotationSection} vocabulary before storage, and anything
     * outside it is dropped rather than rejected.
     *
     * <p>That is not leniency for its own sake. The other side of the comparison — a lead's service
     * list — is normalized the same way, so a service a package has no section for (visa, insurance,
     * passport, or whatever an integration invents next) would otherwise be counted as "missing" from
     * every template forever and quietly depress every score.
     */
    private void applyServices(QuotationTemplateRequest request, QuotationTemplate template) {
        template.getServices().clear();
        if (request.getServices() == null || request.getServices().isEmpty()) return;

        Set<String> keys = new LinkedHashSet<>();
        for (String raw : request.getServices()) {
            if (!StringUtils.hasText(raw)) continue;
            String candidate = raw.trim().toLowerCase(java.util.Locale.ROOT);
            // Accept either spelling: the lead-side service id ("hotel") or the section key
            // ("addons"). normalize() maps the first; the second is matched directly.
            List<String> mapped = QuotationSection.normalize(List.of(candidate));
            if (!mapped.isEmpty()) {
                keys.addAll(mapped);
                continue;
            }
            for (QuotationSection section : QuotationSection.values()) {
                if (section.key().equals(candidate)) keys.add(section.key());
            }
        }
        template.getServices().addAll(keys);
    }

    private void applyItinerary(QuotationTemplateRequest request, QuotationTemplate template, Long tenantId) {
        template.getItinerary().clear();
        if (request.getItinerary() == null) return;

        List<QuotationTemplateRequest.ItineraryDay> days = new ArrayList<>(request.getItinerary());
        days.sort(Comparator.comparing(QuotationTemplateRequest.ItineraryDay::getDayNumber));

        for (QuotationTemplateRequest.ItineraryDay day : days) {
            City city = resolveCity(day.getCityId(), tenantId);
            String cityName = StringUtils.hasText(day.getCityName())
                    ? day.getCityName().trim()
                    : (city != null ? city.getName() : null);

            template.addItineraryDay(QuotationTemplateItinerary.builder()
                    .dayNumber(day.getDayNumber())
                    .cityId(day.getCityId())
                    .cityName(cityName)
                    .destinationName(trimToNull(day.getDestinationName()))
                    .nights(day.getNights())
                    .title(trimToNull(day.getTitle()))
                    .description(day.getDescription())
                    .pricePerPax(day.getPricePerPax())
                    .build());
        }
    }

    private void applyHotels(QuotationTemplateRequest request, QuotationTemplate template) {
        template.getHotels().clear();
        if (request.getHotels() == null) return;

        int sortOrder = 0;
        for (QuotationTemplateRequest.HotelItem hotel : request.getHotels()) {
            template.addHotel(QuotationTemplateHotel.builder()
                    .sortOrder(sortOrder++)
                    .name(trimToNull(hotel.getName()))
                    .city(trimToNull(hotel.getCity()))
                    .stars(hotel.getStars())
                    .roomType(trimToNull(hotel.getRoomType()))
                    .mealPlan(trimToNull(hotel.getMealPlan()))
                    .refundable(hotel.getRefundable())
                    .pricePerRoom(hotel.getPricePerRoom())
                    .rooms(hotel.getRooms())
                    .nights(hotel.getNights())
                    .imagePath(hotel.getImagePath())
                    .build());
        }
    }

    /** Explicit wins; otherwise the itinerary's nights add up to the package duration. */
    private Integer resolveDurationNights(QuotationTemplateRequest request, QuotationTemplate template) {
        if (request.getDurationNights() != null) return request.getDurationNights();
        int sum = template.getItinerary().stream()
                .map(QuotationTemplateItinerary::getNights)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return sum > 0 ? sum : null;
    }

    /**
     * A {@code cityId} must belong to this tenant. There is no DB-level FK (the module keeps the id
     * as a logical reference, like {@code Booking.leadId}), so this is the only thing standing
     * between a template and a city from another tenant.
     */
    private City resolveCity(Long cityId, Long tenantId) {
        if (cityId == null) return null;
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }

    private static void replaceAll(List<String> target, List<String> source) {
        target.clear();
        if (source != null) {
            source.stream().filter(StringUtils::hasText).map(String::trim).forEach(target::add);
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId claim.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}