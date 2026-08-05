package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotationtemplate.dto.ApplyTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateResponse;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplatePreview;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplateRequest;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface QuotationTemplateService {

    QuotationTemplateResponse create(QuotationTemplateRequest request);

    QuotationTemplateResponse update(UUID publicId, QuotationTemplateRequest request);

    QuotationTemplateResponse getByPublicId(UUID publicId);

    Page<QuotationTemplateResponse> search(String keyword, Boolean active,
                                           int page, int size, String sortBy, String sortDir);

    /** Soft delete. The template stays out of matching but past quotations are untouched. */
    void delete(UUID publicId);

    /**
     * Clone the template into a new DRAFT quotation for the given lead and return it, ready for the
     * agent to open in the builder and edit.
     */
    QuotationResponseDto apply(UUID templatePublicId, ApplyTemplateRequest request);

    /**
     * What "Save as Template" would capture from this quotation — derived defaults, resolved cities,
     * what is dropped, and the nearest existing template. Writes nothing.
     */
    SaveAsTemplatePreview previewFromQuotation(UUID quotationPublicId);

    /** Capture a quotation as a reusable package, creating a new template or updating an existing one. */
    QuotationTemplateResponse saveFromQuotation(SaveAsTemplateRequest request);
}