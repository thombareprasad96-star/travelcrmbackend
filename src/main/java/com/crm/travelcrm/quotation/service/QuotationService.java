package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.quotation.dto.QuotationEmailRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface QuotationService {

    QuotationResponseDto create(QuotationRequestDto request);

    QuotationResponseDto update(UUID publicId, QuotationRequestDto request);

    QuotationResponseDto getByPublicId(UUID publicId);

    Page<QuotationSummaryDto> search(String keyword, QuotationStage stage, UUID leadId,
                                     int page, int size, String sortBy, String sortDir);

    List<QuotationSummaryDto> getByLead(UUID leadPublicId);

    void delete(UUID publicId);

    QuotationResponseDto updateStage(UUID publicId, QuotationStage stage);

    QuotationResponseDto duplicate(UUID publicId);

    /** Create a new version (deep copy + increment), render its PDF and store it on Cloudinary. */
    QuotationResponseDto newVersion(UUID publicId);

    /** Render the quotation as an A4 PDF document. */
    byte[] generatePdf(UUID publicId);

    /** Resolve the quotation PDF — the stored Cloudinary URL if present, else freshly rendered bytes. */
    QuotationPdfResource getPdf(UUID publicId);

    /** Email the generated PDF to the given recipient. */
    void sendEmail(UUID publicId, QuotationEmailRequestDto request);

    /** Build a shareable link that resolves to the quotation PDF. */
    String getShareLink(UUID publicId);
}