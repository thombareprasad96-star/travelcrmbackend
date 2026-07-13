package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.DuplicateResourceException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.service.QuotationService;
import com.crm.travelcrm.quotationtemplate.dto.ApplyTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateResponse;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateHotel;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateItinerary;
import com.crm.travelcrm.quotationtemplate.mapper.QuotationTemplateMapper;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotationTemplateResponse create(QuotationTemplateRequest request) {
        Long tenantId = currentTenantId();
        String name = request.getName().trim();
        if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)) {
            throw new DuplicateResourceException("A template named '" + name + "' already exists");
        }

        QuotationTemplate template = new QuotationTemplate();
        template.setTenantId(tenantId);
        applyRequest(request, template, tenantId);

        QuotationTemplate saved = templateRepository.save(template);
        log.info("Quotation template created | publicId: {} | tenantId: {}", saved.getPublicId(), tenantId);
        return mapper.toResponse(saved);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public QuotationTemplateResponse update(UUID publicId, QuotationTemplateRequest request) {
        Long tenantId = currentTenantId();
        QuotationTemplate template = loadOwned(publicId, tenantId);

        String name = request.getName().trim();
        if (templateRepository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(
                tenantId, name, template.getId())) {
            throw new DuplicateResourceException("A template named '" + name + "' already exists");
        }

        applyRequest(request, template, tenantId);
        QuotationTemplate saved = templateRepository.save(template);
        log.info("Quotation template updated | publicId: {} | tenantId: {}", publicId, tenantId);
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
        QuotationResponseDto quotation = quotationService.create(payload);
        log.info("Template {} applied to lead {} -> quotation {}",
                templatePublicId, request.getLeadId(), quotation.getPublicId());
        return quotation;
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

        replaceAll(template.getInclusions(), request.getInclusions());
        replaceAll(template.getExclusions(), request.getExclusions());

        template.setDurationNights(resolveDurationNights(request, template));
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