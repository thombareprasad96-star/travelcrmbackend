package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.quotation.dto.QuotationEmailRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.entity.*;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.mapper.QuotationMapper;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.quotation.specification.QuotationSpecification;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuotationServiceImpl implements QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationMapper quotationMapper;
    private final QuotationPdfService quotationPdfService;
    private final CloudinaryService cloudinaryService;
    private final LeadAccessGuard leadAccessGuard;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:no-reply@travelcrm.local}")
    private String mailFrom;

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    // ── Create ──────────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto create(QuotationRequestDto request) {
        Long tenantId = currentTenantId();

        Quotation q = new Quotation();
        q.setTenantId(tenantId);
        quotationMapper.applyRequest(request, q);

        // Per-lead auto versioning: the Nth live quotation for a lead becomes vN.0
        // (1st → v1.0, 2nd → v2.0, …). Sorted latest-first, so the newest version is on top.
        int versionNumber = 1;
        if (request.getLeadId() != null) {
            long existing = quotationRepository
                    .countByLeadPublicIdAndTenantIdAndDeletedAtIsNull(request.getLeadId(), tenantId);
            versionNumber = (int) existing + 1;
        }
        q.setVersionNumber(versionNumber);
        q.setVersion("v" + versionNumber + ".0");

        q.setQuoteNo((int) (quotationRepository.countByTenantIdAndParentQuotationIdIsNull(tenantId) + 1));
        linkLeadAndSnapshot(q, request.getLeadId());

        Quotation saved = quotationRepository.save(q);
        log.info("Quotation created | publicId: {} | tenantId: {}", saved.getPublicId(), tenantId);
        return quotationMapper.toResponse(saved);
    }

    // ── Update ──────────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto update(UUID publicId, QuotationRequestDto request) {
        Long tenantId = currentTenantId();

        Quotation q = quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));

        quotationMapper.applyRequest(request, q);
        // Content changed — drop the cached PDF so the next GET /pdf regenerates it.
        q.setPdfUrl(null);
        // Re-link the lead only if the client sent one (keeps the existing snapshot otherwise)
        if (request.getLeadId() != null) {
            linkLeadAndSnapshot(q, request.getLeadId());
        }

        Quotation saved = quotationRepository.save(q);
        log.info("Quotation updated | publicId: {} | tenantId: {}", publicId, tenantId);
        return quotationMapper.toResponse(saved);
    }

    // ── Read ────────────────────────────────────────────────────────────────--

    @Override
    @Transactional(readOnly = true)
    public QuotationResponseDto getByPublicId(UUID publicId) {
        return quotationMapper.toResponse(loadOwned(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuotationSummaryDto> search(String keyword, QuotationStage stage, UUID leadId,
                                            int page, int size, String sortBy, String sortDir) {
        Long tenantId = currentTenantId();

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<Quotation> spec = QuotationSpecification.base(tenantId)
                .and(QuotationSpecification.search(keyword))
                .and(QuotationSpecification.filter(stage, leadId, null, null));

        return quotationRepository.findAll(spec, pageable).map(quotationMapper::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuotationSummaryDto> getByLead(UUID leadPublicId) {
        Long tenantId = currentTenantId();
        return quotationRepository
                .findAllByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(leadPublicId, tenantId)
                .stream()
                .map(quotationMapper::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationSummaryDto getLatestByLead(UUID leadPublicId) {
        Long tenantId = currentTenantId();
        return quotationRepository
                .findFirstByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(leadPublicId, tenantId)
                .map(quotationMapper::toSummary)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationRefDto getLatestRefByLead(UUID leadPublicId) {
        return getLatestRefsByLeads(List.of(leadPublicId)).get(leadPublicId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, QuotationRefDto> getLatestRefsByLeads(Collection<UUID> leadPublicIds) {
        // Guard the empty case — never fire an "IN ()" query.
        if (leadPublicIds == null || leadPublicIds.isEmpty()) {
            return Map.of();
        }
        Long tenantId = currentTenantId();
        // Rows arrive createdAt DESC, id DESC — keep the first (latest) ref seen per lead.
        Map<UUID, QuotationRefDto> result = new LinkedHashMap<>();
        for (QuotationRepository.LatestQuotationRef row :
                quotationRepository.findLatestRefsForLeads(leadPublicIds, tenantId)) {
            result.putIfAbsent(row.getLeadPublicId(),
                    QuotationRefDto.builder().publicId(row.getQuotationPublicId()).build());
        }
        return result;
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Quotation q = loadOwned(publicId);
        q.softDelete(currentUserEmail());
        quotationRepository.save(q);
        log.info("Quotation soft-deleted | publicId: {}", publicId);
    }

    // ── Stage change ────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto updateStage(UUID publicId, QuotationStage stage) {
        Quotation q = loadOwned(publicId);
        q.setStage(stage);
        Quotation saved = quotationRepository.save(q);
        log.info("Quotation stage changed | publicId: {} -> {}", publicId, stage);
        return quotationMapper.toResponse(saved);
    }

    // ── Duplicate (new version) ────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto duplicate(UUID publicId) {
        Long tenantId = currentTenantId();
        Quotation copy = buildNextVersion(loadOwned(publicId), tenantId);
        Quotation saved = quotationRepository.save(copy);
        log.info("Quotation duplicated -> {} (v{})", saved.getPublicId(), saved.getVersionNumber());
        return quotationMapper.toResponse(saved);
    }

    // ── New version (deep copy + increment + PDF to Cloudinary) ───────────────--

    @Override
    @Transactional
    public QuotationResponseDto newVersion(UUID publicId) {
        Long tenantId = currentTenantId();
        Quotation copy = buildNextVersion(loadOwned(publicId), tenantId);
        Quotation saved = quotationRepository.save(copy);

        // Render + upload the PDF, then persist its URL. A failure here must NOT lose
        // the new version — we leave pdfUrl null and fall back to on-the-fly rendering
        // on GET /pdf.
        try {
            saved.setPdfUrl(generateAndStorePdf(saved));
            quotationRepository.save(saved);
        } catch (Exception ex) {
            log.error("New version {} created, but PDF generation/upload failed: {}",
                    saved.getPublicId(), ex.getMessage(), ex);
        }

        log.info("New version created | publicId: {} | v{} | root id: {}",
                saved.getPublicId(), saved.getVersionNumber(), saved.getParentQuotationId());
        return quotationMapper.toResponse(saved);
    }

    // ── PDF ─────────────────────────────────────────────────────────────────--

    @Override
    @Transactional(readOnly = true)
    public byte[] generatePdf(UUID publicId) {
        QuotationResponseDto dto = quotationMapper.toResponse(loadOwned(publicId));
        return quotationPdfService.render(dto);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPdfResource getPdf(UUID publicId) {
        Quotation q = loadOwned(publicId);
        if (StringUtils.hasText(q.getPdfUrl())) {
            return QuotationPdfResource.remote(q.getPdfUrl());
        }
        return QuotationPdfResource.inline(quotationPdfService.render(quotationMapper.toResponse(q)));
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPdfResource getPublicPdf(UUID publicId) {
        // Capability-URL access: no auth / no tenant context. Lookup is by the globally-unique
        // publicId only. Read-only — never mutates and never exposes anything but this one PDF.
        Quotation q = quotationRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        if (StringUtils.hasText(q.getPdfUrl())) {
            return QuotationPdfResource.remote(q.getPdfUrl());
        }
        return QuotationPdfResource.inline(quotationPdfService.render(quotationMapper.toResponse(q)));
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationResponseDto getPublicByPublicId(UUID publicId) {
        // Capability-URL access (no auth/tenant) — lookup by the globally-unique publicId.
        Quotation q = quotationRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        QuotationResponseDto dto = quotationMapper.toResponse(q);
        // Strip internal/agent-only fields from the public, customer-facing payload.
        dto.setCreatedBy(null);
        dto.setLeadId(null);
        return dto;
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void sendEmail(UUID publicId, QuotationEmailRequestDto request) {
        QuotationResponseDto dto = quotationMapper.toResponse(loadOwned(publicId));
        byte[] pdf = quotationPdfService.render(dto);

        String subject = StringUtils.hasText(request.getSubject())
                ? request.getSubject()
                : "Travel Quotation - " + dto.getTitle();
        String body = StringUtils.hasText(request.getMessage())
                ? request.getMessage()
                : "Dear " + safe(dto.getCustomer() != null ? dto.getCustomer().getName() : null, "Customer")
                + ",\n\nPlease find your travel quotation attached.\n\nRegards,\nTeam";
        String fileName = "quotation-" + dto.getTitle().replaceAll("[^a-zA-Z0-9-_]", "_") + ".pdf";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailFrom);
            helper.setTo(request.getToEmail());
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(fileName, new org.springframework.core.io.ByteArrayResource(pdf),
                    "application/pdf");
            mailSender.send(message);
            log.info("Quotation {} emailed to {}", publicId, request.getToEmail());
        } catch (Exception ex) {
            log.error("Failed to email quotation {}: {}", publicId, ex.getMessage(), ex);
            throw new BusinessException("Failed to send quotation email: " + ex.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    // ── Share link ──────────────────────────────────────────────────────────--

    @Override
    @Transactional(readOnly = true)
    public String getShareLink(UUID publicId) {
        // Existence + ownership check
        loadOwned(publicId);
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        // Public, shareable link (no auth) — recipients open it directly from WhatsApp/email.
        return base + "/api/public/quotations/" + publicId + "/pdf";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ════════════════════════════════════════════════════════════════════════

    private Quotation loadOwned(UUID publicId) {
        Long tenantId = currentTenantId();
        return quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
    }

    /**
     * Resolve the lead by its publicId (tenant-scoped) and snapshot the customer
     * details onto the quotation so the PDF is stable.
     */
    private void linkLeadAndSnapshot(Quotation q, UUID leadPublicId) {
        if (leadPublicId == null) {
            return;
        }
        // Resolve through the central guard so the Lead module's tenant + row-level scope is
        // enforced here too — a user must not snapshot a lead they aren't allowed to see.
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_READ");

        q.setLeadId(lead.getId());
        q.setLeadPublicId(lead.getPublicId());
        q.setLeadStage(lead.getLeadStage());
        q.setCustomerName(lead.getCustomerName());
        q.setCustomerPhone(lead.getPhone());
        q.setCustomerEmail(lead.getEmail());
        q.setAdults(lead.getAdults());
        q.setChildren(lead.getChildren());
        q.setInfants(lead.getInfants());
        q.setTravelDate(lead.getTravelDate());
        q.setDestination(resolveDestination(lead));
    }

    private String resolveDestination(Lead lead) {
        String fromItinerary = lead.getItinerary() == null ? "" :
                lead.getItinerary().stream()
                        .map(LeadItinerary::getDestination)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .collect(Collectors.joining(", "));
        if (StringUtils.hasText(fromItinerary)) {
            return fromItinerary;
        }
        return StringUtils.hasText(lead.getDepartCountry()) ? lead.getDepartCountry() : null;
    }

    /**
     * Builds (unsaved) the next version of {@code src}: a deep copy with stage reset to
     * DRAFT, versionNumber = max in the family + 1, and parentQuotationId pointing at the
     * family root (so every version of a quotation lives under one root id).
     */
    private Quotation buildNextVersion(Quotation src, Long tenantId) {
        Long rootId = src.getParentQuotationId() != null ? src.getParentQuotationId() : src.getId();
        int nextVersion = quotationRepository.findMaxVersionInFamily(rootId, tenantId) + 1;

        Quotation copy = copyForDuplicate(src, "v" + nextVersion + ".0");
        copy.setTenantId(tenantId);
        copy.setVersionNumber(nextVersion);
        copy.setParentQuotationId(rootId);
        copy.setPdfUrl(null);
        return copy;
    }

    /** Renders the quotation PDF and stores it on Cloudinary at quotations/{publicId}.pdf. */
    private String generateAndStorePdf(Quotation q) {
        byte[] pdf = quotationPdfService.render(quotationMapper.toResponse(q));
        String quotationNo = q.getPublicId().toString();
        return cloudinaryService.uploadRaw(pdf, "quotations/" + quotationNo + ".pdf");
    }

    /** Deep-copies a quotation (sans id/publicId/audit) for the duplicate / new-version features. */
    private Quotation copyForDuplicate(Quotation src, String newVersion) {
        Quotation c = new Quotation();
        c.setLeadId(src.getLeadId());
        c.setLeadPublicId(src.getLeadPublicId());
        c.setLeadStage(src.getLeadStage());
        c.setQuoteNo(src.getQuoteNo());     // versions share the family's quote number
        c.setTitle(src.getTitle());
        c.setVersion(newVersion);
        c.setStage(QuotationStage.DRAFT);
        c.setCoverImageUrl(src.getCoverImageUrl());
        c.setNotes(src.getNotes());

        c.setCustomerName(src.getCustomerName());
        c.setCustomerPhone(src.getCustomerPhone());
        c.setCustomerEmail(src.getCustomerEmail());
        c.setDestination(src.getDestination());
        c.setTravelDate(src.getTravelDate());
        c.setAdults(src.getAdults());
        c.setChildren(src.getChildren());
        c.setInfants(src.getInfants());

        c.setFlightIncluded(src.getFlightIncluded());
        c.setFlightTitle(src.getFlightTitle());
        c.setFlightAmount(src.getFlightAmount());
        c.setFlightJourney(src.getFlightJourney());
        for (QuotationFlightSegment s : src.getFlightSegments()) {
            QuotationFlightSegment ns = QuotationFlightSegment.builder()
                    .airline(s.getAirline()).flightNo(s.getFlightNo()).travelClass(s.getTravelClass())
                    .fromLocation(s.getFromLocation()).toLocation(s.getToLocation())
                    .depDate(s.getDepDate()).depTime(s.getDepTime())
                    .arrDate(s.getArrDate()).arrTime(s.getArrTime())
                    .duration(s.getDuration()).cabinBaggage(s.getCabinBaggage())
                    .checkinBaggage(s.getCheckinBaggage()).pricePerPax(s.getPricePerPax()).pax(s.getPax())
                    .build();
            for (QuotationFlightConnection con : s.getConnections()) {
                ns.addConnection(QuotationFlightConnection.builder()
                        .airline(con.getAirline()).flightNo(con.getFlightNo())
                        .fromLocation(con.getFromLocation()).toLocation(con.getToLocation())
                        .depDate(con.getDepDate()).depTime(con.getDepTime())
                        .arrDate(con.getArrDate()).arrTime(con.getArrTime())
                        .build());
            }
            c.addFlightSegment(ns);
        }

        c.setHotelIncluded(src.getHotelIncluded());
        c.setHotelTitle(src.getHotelTitle());
        c.setHotelAmount(src.getHotelAmount());
        c.setHotelNotes(src.getHotelNotes());
        for (QuotationHotel h : src.getHotels()) {
            c.addHotel(QuotationHotel.builder()
                    .name(h.getName()).city(h.getCity()).checkIn(h.getCheckIn()).checkOut(h.getCheckOut())
                    .roomType(h.getRoomType()).mealPlan(h.getMealPlan()).refundable(h.getRefundable())
                    .stars(h.getStars())
                    .pricePerRoom(h.getPricePerRoom()).rooms(h.getRooms()).imagePath(h.getImagePath())
                    .build());
        }

        c.setSightseeingIncluded(src.getSightseeingIncluded());
        c.setSightseeingTitle(src.getSightseeingTitle());
        c.setSightseeingAmount(src.getSightseeingAmount());
        c.setSightseeingNotes(src.getSightseeingNotes());
        for (QuotationSightseeingDay d : src.getSightseeingDays()) {
            QuotationSightseeingDay nd = QuotationSightseeingDay.builder()
                    .dayNumber(d.getDayNumber()).date(d.getDate())
                    .pricePerPax(d.getPricePerPax()).pax(d.getPax())
                    .build();
            for (QuotationSightseeingActivity a : d.getActivities()) {
                QuotationSightseeingActivity na = QuotationSightseeingActivity.builder()
                        .attraction(a.getAttraction()).startTime(a.getStartTime())
                        .description(a.getDescription()).transfer(a.getTransfer()).imagePath(a.getImagePath())
                        .build();
                na.getMeals().addAll(a.getMeals());
                nd.addActivity(na);
            }
            c.addSightseeingDay(nd);
        }

        c.setCruiseIncluded(src.getCruiseIncluded());
        c.setCruiseTitle(src.getCruiseTitle());
        c.setCruiseAmount(src.getCruiseAmount());
        for (QuotationCruise cr : src.getCruises()) {
            c.addCruise(QuotationCruise.builder()
                    .name(cr.getName()).type(cr.getType()).depPort(cr.getDepPort()).arrPort(cr.getArrPort())
                    .depDate(cr.getDepDate()).nights(cr.getNights()).cabin(cr.getCabin()).price(cr.getPrice())
                    .pricePerPax(cr.getPricePerPax()).pax(cr.getPax())
                    .build());
        }

        c.setVehicleIncluded(src.getVehicleIncluded());
        c.setVehicleTitle(src.getVehicleTitle());
        c.setVehicleAmount(src.getVehicleAmount());
        for (QuotationVehicle v : src.getVehicles()) {
            c.addVehicle(QuotationVehicle.builder()
                    .type(v.getType()).pickup(v.getPickup()).drop(v.getDrop())
                    .startDate(v.getStartDate()).endDate(v.getEndDate()).price(v.getPrice())
                    .pricePerVehicle(v.getPricePerVehicle()).qty(v.getQty()).notes(v.getNotes())
                    .build());
        }

        c.setAddonIncluded(src.getAddonIncluded());
        c.setAddonTitle(src.getAddonTitle());
        c.setAddonAmount(src.getAddonAmount());
        for (QuotationAddon a : src.getAddons()) {
            c.addAddon(QuotationAddon.builder()
                    .serviceType(a.getServiceType()).description(a.getDescription())
                    .quantity(a.getQuantity()).pricePerUnit(a.getPricePerUnit()).included(a.getIncluded())
                    .build());
        }

        c.getInclusions().addAll(src.getInclusions());
        c.getExclusions().addAll(src.getExclusions());
        c.getPaymentPolicies().addAll(src.getPaymentPolicies());
        c.getCancellationPolicies().addAll(src.getCancellationPolicies());
        c.getBookingTerms().addAll(src.getBookingTerms());

        c.setDiscount(src.getDiscount());
        c.setDiscountType(src.getDiscountType());
        c.setTax(src.getTax());
        c.setMarkup(src.getMarkup());
        return c;
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

    private static String safe(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}