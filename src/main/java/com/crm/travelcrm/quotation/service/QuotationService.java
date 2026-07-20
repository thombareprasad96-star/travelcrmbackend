package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.quotation.dto.PublicQuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationEmailRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationWhatsAppRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface QuotationService {

    QuotationResponseDto create(QuotationRequestDto request);

    QuotationResponseDto update(UUID publicId, QuotationRequestDto request);

    QuotationResponseDto getByPublicId(UUID publicId);

    Page<QuotationSummaryDto> search(String keyword, QuotationStage stage, UUID leadId,
                                     int page, int size, String sortBy, String sortDir);

    List<QuotationSummaryDto> getByLead(UUID leadPublicId);

    /** Latest quotation (full summary) for a single lead (newest createdAt, id DESC tiebreak), or {@code null}. */
    QuotationSummaryDto getLatestByLead(UUID leadPublicId);

    /** Minimal ref (publicId only) to a lead's latest quotation — for the lead list. {@code null} if none. */
    QuotationRefDto getLatestRefByLead(UUID leadPublicId);

    /** Minimal refs to the latest quotation per lead for a batch of lead publicIds — one lean query. */
    Map<UUID, QuotationRefDto> getLatestRefsByLeads(Collection<UUID> leadPublicIds);

    void delete(UUID publicId);

    QuotationResponseDto updateStage(UUID publicId, QuotationStage stage);

    QuotationResponseDto duplicate(UUID publicId);

    /** Create a new version (deep copy + increment), render its PDF and store it on Cloudinary. */
    QuotationResponseDto newVersion(UUID publicId);

    /** Render the quotation as an A4 PDF document. */
    byte[] generatePdf(UUID publicId);

    /** Resolve the quotation PDF — the stored Cloudinary URL if present, else freshly rendered bytes. */
    QuotationPdfResource getPdf(UUID publicId);

    /**
     * Same PDF, rendered in a chosen design instead of the quotation's saved one.
     *
     * <p>A ONE-OFF override for the download dialog: it does not persist, so downloading a Premium
     * copy of a Classic quotation leaves the quotation Classic — the customer's share link keeps
     * showing what the agent actually chose in the builder. Null style ⇒ the saved one.
     *
     * <p>Only possible because quotation PDFs are rendered on demand; while a copy was cached on
     * Cloudinary, "render this one differently" would have fought the cache.
     */
    QuotationPdfResource getPdf(UUID publicId, com.crm.travelcrm.quotation.enums.TemplateStyle style);

    /**
     * Persist the design this quotation is shown in — used by the SHARE dialog.
     *
     * <p>Unlike the download override, this one MUST stick: the customer opens the weblink later, and
     * the server renders whatever is stored. A dedicated one-field endpoint rather than a full
     * {@code update()} on purpose — update() re-maps every section from the request, so routing a
     * design change through it would make "share as Premium" capable of rewriting the quotation's
     * contents from a stale client payload.
     */
    QuotationResponseDto setTemplateStyle(UUID publicId,
                                          com.crm.travelcrm.quotation.enums.TemplateStyle style);

    /** Public (unauthenticated) PDF for the share link — looked up by publicId only, no tenant scope. */
    QuotationPdfResource getPublicPdf(UUID publicId);

    /** Public (unauthenticated) quotation JSON for the web-format share page (/q/{publicId}). */
    PublicQuotationResponseDto getPublicByPublicId(UUID publicId);

    /** Email the generated PDF to the given recipient. */
    void sendEmail(UUID publicId, QuotationEmailRequestDto request);

    /** Send the shareable quotation link to the customer over WhatsApp. */
    void sendWhatsApp(UUID publicId, QuotationWhatsAppRequestDto request);

    /** Build a shareable link that resolves to the quotation PDF. */
    String getShareLink(UUID publicId);
}