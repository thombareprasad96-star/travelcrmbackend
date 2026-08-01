package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.settings.service.EmailAuditService;
import com.crm.travelcrm.settings.service.TenantMailSenderFactory;
import com.crm.travelcrm.settings.service.WhatsAppMessagingService;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.repository.DestinationRepository;
import com.crm.travelcrm.quotation.dto.QuotationEmailRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationWhatsAppRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.PublicQuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.entity.*;
import com.crm.travelcrm.quotation.enums.QuotationSection;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.mapper.QuotationMapper;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.quotation.specification.QuotationSpecification;
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuotationServiceImpl implements QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationMapper quotationMapper;
    private final QuotationPdfService quotationPdfService;
    private final LeadAccessGuard leadAccessGuard;
    private final TenantMailSenderFactory tenantMailSenderFactory;
    private final EmailAuditService emailAudit;
    private final WhatsAppMessagingService whatsAppMessaging;
    private final SubAgentScope subAgentScope;
    private final DestinationRepository destinationRepository;

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    // ── Create ──────────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto create(QuotationRequestDto request) {
        Long tenantId = currentTenantId();
        log.debug("create() | tenantId={} | leadPublicId={} | title='{}' | requestedStage={}",
                tenantId, request.getLeadId(), request.getTitle(), request.getQuotationStage());

        Quotation q = new Quotation();
        q.setTenantId(tenantId);
        quotationMapper.applyRequest(request, q);
        log.debug("Mapped request -> sections: flightIncluded={} segments={} | hotels={} | sightseeingDays={} | cruises={} | vehicles={} | addons={} | inclusions={} exclusions={}",
                q.getFlightIncluded(), q.getFlightSegments().size(), q.getHotels().size(),
                q.getSightseeingDays().size(), q.getCruises().size(), q.getVehicles().size(),
                q.getAddons().size(), q.getInclusions().size(), q.getExclusions().size());

        // Per-lead auto versioning: the Nth live quotation for a lead becomes vN.0
        // (1st → v1.0, 2nd → v2.0, …). Sorted latest-first, so the newest version is on top.
        int versionNumber = 1;
        if (request.getLeadId() != null) {
            long existing = quotationRepository
                    .countByLeadPublicIdAndTenantIdAndDeletedAtIsNull(request.getLeadId(), tenantId);
            versionNumber = (int) existing + 1;
            log.debug("Lead {} already has {} live quotation(s) -> assigning version v{}.0",
                    request.getLeadId(), existing, versionNumber);
        }
        q.setVersionNumber(versionNumber);
        q.setVersion("v" + versionNumber + ".0");

        q.setQuoteNo((int) (quotationRepository.countByTenantIdAndParentQuotationIdIsNull(tenantId) + 1));
        log.debug("Assigned quoteNo={} | version={}", q.getQuoteNo(), q.getVersion());
        Lead lead = linkLeadAndSnapshot(q, request.getLeadId());
        applyDestinationCoverImage(q, request.getDestinationId(), tenantId, lead);

        Quotation saved = quotationRepository.save(q);
        log.info("Quotation created | publicId: {} | tenantId: {}", saved.getPublicId(), tenantId);
        if (log.isDebugEnabled()) {
            log.debug("Persisted quotation {} | quoteNo={} | version={} | grandTotal={}",
                    saved.getPublicId(), saved.getQuoteNo(), saved.getVersion(),
                    quotationMapper.computeTotals(saved).getGrandTotal());
        }
        return quotationMapper.toResponse(saved);
    }

    // ── Update ──────────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto update(UUID publicId, QuotationRequestDto request) {
        Long tenantId = currentTenantId();
        log.debug("update() | publicId={} | tenantId={} | newLeadPublicId={} | title='{}'",
                publicId, tenantId, request.getLeadId(), request.getTitle());

        Quotation q = quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        subAgentScope.assertVisible(q, publicId);   // sub-agent may only edit its own quotation

        quotationMapper.applyRequest(request, q);
        // Content changed — drop the cached PDF so the next GET /pdf regenerates it.
        q.setPdfUrl(null);
        log.debug("Re-mapped quotation {} | pdfUrl cleared (will re-render on next /pdf) | sections: hotels={} segments={} sightseeingDays={} cruises={} vehicles={} addons={}",
                publicId, q.getHotels().size(), q.getFlightSegments().size(), q.getSightseeingDays().size(),
                q.getCruises().size(), q.getVehicles().size(), q.getAddons().size());
        // Re-link the lead only if the client sent one (keeps the existing snapshot otherwise)
        Lead lead = null;

        if (request.getLeadId() != null) {
            lead = linkLeadAndSnapshot(q, request.getLeadId());
        }
        applyDestinationCoverImage(
                q,
                request.getDestinationId(),
                tenantId,
                lead
        );

        Quotation saved = quotationRepository.save(q);
        log.info("Quotation updated | publicId: {} | tenantId: {}", publicId, tenantId);
        if (log.isDebugEnabled()) {
            log.debug("Quotation {} updated | version={} | grandTotal={}",
                    publicId, saved.getVersion(), quotationMapper.computeTotals(saved).getGrandTotal());
        }
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
        log.debug("search() | tenantId={} | keyword='{}' | stage={} | leadId={} | page={} size={} sort={} {}",
                tenantId, keyword, stage, leadId, page, size, sortBy, sortDir);

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<Quotation> spec = QuotationSpecification.base(tenantId)
                .and(QuotationSpecification.search(keyword))
                .and(QuotationSpecification.filter(stage, leadId, null, null));
        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) spec = spec.and(QuotationSpecification.ownedBy(ownerFilter));

        Page<QuotationSummaryDto> result =
                quotationRepository.findAll(spec, pageable).map(quotationMapper::toSummary);
        log.debug("search() -> {} row(s) on page {} of {} (total {})",
                result.getNumberOfElements(), page, result.getTotalPages(), result.getTotalElements());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuotationSummaryDto> getByLead(UUID leadPublicId) {
        Long tenantId = currentTenantId();
        Long ownerFilter = subAgentScope.ownerFilter();
        return quotationRepository
                .findAllByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(leadPublicId, tenantId)
                .stream()
                .filter(x -> ownerFilter == null || ownerFilter.equals(x.getOwnerUserId()))
                .map(quotationMapper::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationSummaryDto getLatestByLead(UUID leadPublicId) {
        Long tenantId = currentTenantId();
        Long ownerFilter = subAgentScope.ownerFilter();
        return quotationRepository
                .findFirstByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(leadPublicId, tenantId)
                .filter(x -> ownerFilter == null || ownerFilter.equals(x.getOwnerUserId()))
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
                    QuotationRefDto.builder()
                            .publicId(row.getQuotationPublicId())
                            // Grand total via the shared pricing formula so it matches the quotation exactly.
                            .grandTotal(QuotationMapper.computeTotals(
                                    row.getFlightAmount(), row.getHotelAmount(), row.getSightseeingAmount(),
                                    row.getCruiseAmount(), row.getVehicleAmount(), row.getAddonAmount(),
                                    row.getDiscount(), row.getDiscountType(), row.getTax(), row.getMarkup(),
                                    null).getGrandTotal())
                            .build());
        }
        return result;
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        log.debug("delete() | publicId={}", publicId);
        Quotation q = loadOwned(publicId);
        q.softDelete(currentUserEmail());
        quotationRepository.save(q);
        log.info("Quotation soft-deleted | publicId: {}", publicId);
    }

    // ── Stage change ────────────────────────────────────────────────────────--

    @Override
    @Transactional
    public QuotationResponseDto updateStage(UUID publicId, QuotationStage stage) {
        log.debug("updateStage() | publicId={} -> {}", publicId, stage);
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
        log.debug("duplicate() | source publicId={}", publicId);
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
        log.debug("newVersion() | source publicId={}", publicId);
        Long tenantId = currentTenantId();
        Quotation copy = buildNextVersion(loadOwned(publicId), tenantId);
        Quotation saved = quotationRepository.save(copy);

        // NO PDF is uploaded anywhere. A quotation PDF carries the customer's name, phone, email,
        // travel dates and pricing, and a Cloudinary raw asset is served from a PUBLIC, unauthenticated
        // URL that outlives the quotation itself — soft-deleting the row could not take it down. That
        // is the same reason BookingPdfService renders invoices/vouchers on the fly and caches nothing
        // (see its javadoc); quotations were the one place that broke the rule.
        //
        // The cache also barely paid for itself: a new version is created as a DRAFT, so the first
        // edit called update() and nulled pdfUrl anyway — leaving an orphaned public PDF that nothing
        // referenced and nothing ever deleted.
        //
        // Both /pdf endpoints render on demand instead. That cost is already paid on v1 and on every
        // edited quotation today.

        log.info("New version created | publicId: {} | v{} | root id: {}",
                saved.getPublicId(), saved.getVersionNumber(), saved.getParentQuotationId());
        return quotationMapper.toResponse(saved);
    }

    // ── PDF ─────────────────────────────────────────────────────────────────--

    @Override
    @Transactional(readOnly = true)
    public byte[] generatePdf(UUID publicId) {
        log.debug("generatePdf() | publicId={}", publicId);
        QuotationResponseDto dto = quotationMapper.toResponse(loadOwned(publicId));
        return quotationPdfService.render(dto);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPdfResource getPdf(UUID publicId) {
        return getPdf(publicId, null);
    }

    @Override
    @Transactional
    public QuotationResponseDto setTemplateStyle(UUID publicId,
                                                 com.crm.travelcrm.quotation.enums.TemplateStyle style) {
        Long tenantId = currentTenantId();
        Quotation q = quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        subAgentScope.assertVisible(q, publicId);   // a sub-agent may only restyle its own quotation

        q.setTemplateStyle(com.crm.travelcrm.quotation.enums.TemplateStyle.orDefault(style));
        Quotation saved = quotationRepository.save(q);
        log.info("Quotation {} template style set to {}", publicId, saved.getTemplateStyle());
        return quotationMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPdfResource getPdf(UUID publicId, com.crm.travelcrm.quotation.enums.TemplateStyle style) {
        Quotation q = loadOwned(publicId);
        QuotationResponseDto dto = quotationMapper.toResponse(q);
        if (style != null) {
            // Overridden on the DTO only — the entity is untouched, so this render cannot change what
            // the customer sees on the share link. readOnly transaction, nothing to flush.
            log.debug("getPdf({}) -> one-off style override {} (saved style stays {})",
                    publicId, style, dto.getTemplateStyle());
            dto.setTemplateStyle(style);
        }
        // ALWAYS rendered here — never a redirect to a stored copy. Rows created before the Cloudinary
        // upload was removed still hold a pdf_url; serving it would keep handing clients a public,
        // undeletable URL for a document full of customer PII. The column is left populated on purpose:
        // it is the only record of which assets are still sitting in Cloudinary's quotations/ folder
        // and therefore the shopping list for the one-time cleanup.
        log.debug("getPdf({}) -> rendering PDF inline", publicId);
        // `dto` — NOT a fresh toResponse(q). Re-mapping here would rebuild the DTO from the entity and
        // silently discard the style override set above, so every download came out in the saved
        // design however the dialog was answered.
        return QuotationPdfResource.inline(quotationPdfService.render(dto));
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPdfResource getPublicPdf(UUID publicId) {
        log.debug("getPublicPdf() | publicId={} (public share link)", publicId);
        // Capability-URL access: no auth / no tenant context. Lookup is by the globally-unique
        // publicId only. Read-only — never mutates and never exposes anything but this one PDF.
        Quotation q = quotationRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        // Rendered, never redirected — see getPdf. This path matters most: it is the link the customer
        // opens from WhatsApp, and a 302 sent their browser to the raw Cloudinary URL, putting a
        // permanent public link to their own PII in their address bar and history.
        log.debug("getPublicPdf({}) -> rendering PDF inline", publicId);
        return QuotationPdfResource.inline(quotationPdfService.render(quotationMapper.toResponse(q)));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicQuotationResponseDto getPublicByPublicId(UUID publicId) {
        // Capability-URL access (no auth/tenant) — lookup by the globally-unique publicId.
        Quotation q = quotationRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        // Project onto a customer-safe WHITELIST DTO — never blacklist-strip the internal one, which
        // still serializes the agent's markup (totals.markup + the pricing block) and internal metadata.
        return toPublic(quotationMapper.toResponse(q));
    }

    /** Copy only the customer-safe fields; markup / pricing / internal metadata are never carried over. */
    private PublicQuotationResponseDto toPublic(QuotationResponseDto d) {
        // Redact free-text notes on the reused section objects (they may hold internal remarks). Safe to
        // mutate: this full DTO is built only to feed the public projection and is discarded afterwards.
        if (d.getHotel() != null)       d.getHotel().setNotes(null);
        if (d.getSightseeing() != null) d.getSightseeing().setNotes(null);

        QuotationResponseDto.Totals t = d.getTotals();
        PublicQuotationResponseDto.PublicTotals totals = t == null ? null
                : PublicQuotationResponseDto.PublicTotals.builder()
                        .subtotal(t.getSubtotal())
                        .discountType(t.getDiscountType())
                        .discount(t.getDiscount())
                        .discountAmount(t.getDiscountAmount())
                        .taxPercent(t.getTaxPercent())
                        .taxAmount(t.getTaxAmount())
                        .grandTotal(t.getGrandTotal())
                        .addonsTotal(t.getAddonsTotal())
                        .perAdult(t.getPerAdult())
                        // markup deliberately omitted
                        .build();

        return PublicQuotationResponseDto.builder()
                .publicId(d.getPublicId())
                .title(d.getTitle())
                .version(d.getVersion())
                .versionNumber(d.getVersionNumber())
                // pdfUrl deliberately NOT projected: on a legacy row it is a public Cloudinary link to
                // this customer's own PII, and this DTO is served unauthenticated. The FE never read it
                // — it builds /api/public/quotations/{id}/pdf itself — so nothing downstream notices.
                .coverImageUrl(d.getCoverImageUrl())
                .templateStyle(d.getTemplateStyle())   // already orDefault'd in toResponse — never null
                .quoteNo(d.getQuoteNo())
                .nights(d.getNights())
                .days(d.getDays())
                .rooms(d.getRooms())
                .customer(d.getCustomer())
                .flight(d.getFlight())
                .hotel(d.getHotel())
                .sightseeing(d.getSightseeing())
                .cruise(d.getCruise())
                .vehicle(d.getVehicle())
                .addons(d.getAddons())
                .inclusions(d.getInclusions())
                .exclusions(d.getExclusions())
                .paymentPolicies(d.getPaymentPolicies())
                .cancellationPolicies(d.getCancellationPolicies())
                .bookingTerms(d.getBookingTerms())
                .totals(totals)
                .build();
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void sendEmail(UUID publicId, QuotationEmailRequestDto request) {
        log.debug("sendEmail() | publicId={} | to={} | customSubject={} | customMessage={}",
                publicId, request.getToEmail(), StringUtils.hasText(request.getSubject()),
                StringUtils.hasText(request.getMessage()));
        QuotationResponseDto dto = quotationMapper.toResponse(loadOwned(publicId));
        byte[] pdf = quotationPdfService.render(dto);
        log.debug("Rendered quotation {} PDF ({} bytes); dispatching email", publicId, pdf.length);

        String subject = StringUtils.hasText(request.getSubject())
                ? request.getSubject()
                : "Travel Quotation - " + dto.getTitle();
        String body = StringUtils.hasText(request.getMessage())
                ? request.getMessage()
                : "Dear " + safe(dto.getCustomer() != null ? dto.getCustomer().getName() : null, "Customer")
                + ",\n\nPlease find your travel quotation attached.\n\nRegards,\nTeam";
        String fileName = "quotation-" + dto.getTitle().replaceAll("[^a-zA-Z0-9-_]", "_") + ".pdf";

        try {
            // Send from the tenant's own SMTP config (Settings → Email); falls back to the
            // application-wide spring.mail sender if this tenant hasn't configured email.
            TenantMailSenderFactory.ResolvedMail mail =
                    tenantMailSenderFactory.resolve(TenantContext.getTenantId());
            MimeMessage message = mail.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(mail.fromName())) {
                helper.setFrom(mail.from(), mail.fromName());
            } else {
                helper.setFrom(mail.from());
            }
            helper.setTo(request.getToEmail());
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(fileName, new org.springframework.core.io.ByteArrayResource(pdf),
                    "application/pdf");
            mail.sender().send(message);
            emailAudit.record(request.getToEmail(), subject, true, null);
            log.info("Quotation {} emailed to {} (from {})", publicId, request.getToEmail(), mail.from());
        } catch (Exception ex) {
            // The audit row keeps the real cause; the client does not. A mail failure's message names
            // the SMTP host, port and auth outcome — infrastructure detail a tenant user must not see.
            log.error("Failed to email quotation {}: {}", publicId, ex.getMessage(), ex);
            emailAudit.record(request.getToEmail(), subject, false, ex.getMessage());
            throw new BusinessException("We couldn't send that email. Please try again shortly.",
                    HttpStatus.BAD_GATEWAY);
        }
    }

    // ── WhatsApp ────────────────────────────────────────────────────────────--

    @Override
    @Transactional(readOnly = true)
    public void sendWhatsApp(UUID publicId, QuotationWhatsAppRequestDto request) {
        Quotation q = loadOwned(publicId);
        QuotationResponseDto dto = quotationMapper.toResponse(q);

        // Explicit toPhone wins; otherwise the quotation's snapshotted customer phone.
        String toPhone = (request != null && StringUtils.hasText(request.getToPhone()))
                ? request.getToPhone()
                : q.getCustomerPhone();
        if (!StringUtils.hasText(toPhone)) {
            throw new BusinessException(
                    "No phone number to send to. Add a customer phone or provide one in the request.",
                    HttpStatus.BAD_REQUEST);
        }

        String customerName = safe(q.getCustomerName(), "Customer");
        String shareLink = getShareLink(publicId);
        log.debug("sendWhatsApp() | publicId={} | to={}", publicId, toPhone);

        // Template body args, in order: {{1}} customer name, {{2}} quotation title, {{3}} share link.
        WhatsAppMessagingService.Result result = whatsAppMessaging.sendPurpose(
                TenantContext.getTenantId(),
                WhatsAppMessagingService.Purpose.QUOTATION,
                toPhone,
                List.of(customerName, dto.getTitle(), shareLink));

        if (!result.success()) {
            // The facade already logged + audited the real cause; the client gets a safe message.
            log.error("Failed to WhatsApp quotation {} to {}: {}", publicId, toPhone, result.errorMessage());
            throw new BusinessException(
                    "We couldn't send that WhatsApp message. Check your WhatsApp settings and try again.",
                    HttpStatus.BAD_GATEWAY);
        }
        log.info("Quotation {} sent via WhatsApp to {}", publicId, toPhone);
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
        Quotation q = quotationRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + publicId));
        // Sub-agent row scope: 404 if a sub-agent doesn't own it (no-op for every other role). This is
        // the single by-id chokepoint — covers get/update-stage/delete/duplicate/new-version/pdf/share.
        subAgentScope.assertVisible(q, publicId);
        return q;
    }

    private void applyDestinationCoverImage(Quotation q, UUID destinationId, Long tenantId, Lead lead) {
        if (StringUtils.hasText(q.getCoverImageUrl())) {
            return;
        }

        resolveDestinationForCover(destinationId, tenantId, lead, q.getDestination())
                .map(Destination::getImagePath)
                .filter(StringUtils::hasText)
                .ifPresent(q::setCoverImageUrl);
    }

    private Optional<Destination> resolveDestinationForCover(
            UUID destinationId, Long tenantId, Lead lead, String snapshotDestination) {

        if (destinationId != null) {
            Optional<Destination> byDestinationPublicId =
                    destinationRepository.findByPublicIdVisibleTo(destinationId, tenantId);
            if (byDestinationPublicId.isPresent()) {
                return byDestinationPublicId;
            }

            Optional<String> itineraryDestination = firstItineraryDestination(lead, destinationId);
            if (itineraryDestination.isPresent()) {
                return firstDestinationByName(itineraryDestination.get(), tenantId);
            }
        }

        return firstDestinationByName(snapshotDestination, tenantId);
    }

    private Optional<String> firstItineraryDestination(Lead lead, UUID itineraryPublicId) {
        if (lead == null || lead.getItinerary() == null || itineraryPublicId == null) {
            return Optional.empty();
        }

        return lead.getItinerary().stream()
                .filter(item -> itineraryPublicId.equals(item.getPublicId()))
                .map(LeadItinerary::getDestination)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private Optional<Destination> firstDestinationByName(String destinationName, Long tenantId) {
        if (!StringUtils.hasText(destinationName)) {
            return Optional.empty();
        }

        return destinationRepository
                .findByNameIgnoreCaseVisibleTo(destinationName.trim(), tenantId)
                .stream()
                .findFirst();
    }

    /**
     * Resolve the lead by its publicId (tenant-scoped) and snapshot the customer
     * details onto the quotation so the PDF is stable.
     */
    private Lead linkLeadAndSnapshot(Quotation q, UUID leadPublicId) {
        if (leadPublicId == null) {
            log.debug("linkLeadAndSnapshot: no leadId on request — skipping lead snapshot");
            return null;
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

        // Snapshot the lead's chosen services. This is the anti-retroactivity guard as much as a
        // convenience: LeadServiceImpl clears and rewrites lead_services on every lead save, so a
        // quotation that read the lead live would silently re-shape itself — including already-sent
        // ones — the next time somebody edited the lead. Lead.services is EAGER, so this is free.
        // Unknown ids (visa/insurance/passport) drop out in normalize(); an empty result is the
        // fail-open "no lead information" case, exactly like the null-leadId early return above.
        q.getAllowedServices().clear();
        q.getAllowedServices().addAll(QuotationSection.normalize(lead.getServices()));

        log.debug("Snapshotted lead {} -> customer='{}' | destination='{}' | pax(a/c/i)={}/{}/{} | travelDate={} | leadStage={}",
                lead.getPublicId(), q.getCustomerName(), q.getDestination(),
                q.getAdults(), q.getChildren(), q.getInfants(), q.getTravelDate(), q.getLeadStage());
        return lead;
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
        log.debug("buildNextVersion | source id={} | familyRootId={} | nextVersion=v{}.0",
                src.getId(), rootId, nextVersion);

        Quotation copy = copyForDuplicate(src, "v" + nextVersion + ".0");
        copy.setTenantId(tenantId);
        copy.setVersionNumber(nextVersion);
        copy.setParentQuotationId(rootId);
        copy.setPdfUrl(null);
        return copy;
    }

    // generateAndStorePdf() removed with the Cloudinary upload — see newVersion(). Quotation PDFs are
    // rendered on demand and never persisted to a public bucket.


    /** Deep-copies a quotation (sans id/publicId/audit) for the duplicate / new-version features. */
    private Quotation copyForDuplicate(Quotation src, String newVersion) {
        Quotation c = new Quotation();
        c.setLeadId(src.getLeadId());
        c.setLeadPublicId(src.getLeadPublicId());
        c.setLeadStage(src.getLeadStage());
        // Same reason as templateStyle below — this copy block drops whatever it omits. Without this
        // the snapshot is lost and every duplicate/new-version silently falls back to the canonical
        // section order, reordering the PDF against the original it was copied from.
        c.getAllowedServices().addAll(src.getAllowedServices());
        c.setQuoteNo(src.getQuoteNo());     // versions share the family's quote number
        c.setTitle(src.getTitle());
        c.setVersion(newVersion);
        c.setStage(QuotationStage.DRAFT);
        c.setCoverImageUrl(src.getCoverImageUrl());
        // The design travels with the family: without this line a Modern quotation's v2 (and every
        // duplicate) silently reverts to Classic — this copy block is manual and drops what it omits.
        c.setTemplateStyle(src.getTemplateStyle());
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
                    // imagePath was the ONE image field this copy block dropped (hotels :653 and
                    // activities :669 both carry theirs) — every new version/duplicate silently lost
                    // its vehicle photos.
                    .imagePath(v.getImagePath())
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
