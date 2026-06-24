package com.crm.travelcrm.quotation.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.quotation.analytics.WeblinkAnalyticsService;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.service.QuotationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Public, unauthenticated access to a quotation PDF — backs the shareable WhatsApp/email link.
 *
 * <p>Capability-URL model: access is granted by knowing the quotation's unguessable
 * {@code publicId} (UUID). Read-only; serves only the one PDF and mutates nothing. No tenant
 * context is required because the lookup is by the globally-unique publicId. This path is
 * permitted (no JWT) in {@code SecurityConfig}.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/quotations")
@RequiredArgsConstructor
public class PublicQuotationController {

    private final QuotationService quotationService;
    private final WeblinkAnalyticsService weblinkAnalyticsService;

    /** Public quotation JSON for the web-format share page (/q/{publicId}). */
    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> getQuotation(
            @PathVariable UUID publicId, HttpServletRequest request) {
        log.info("GET /api/public/quotations/{} (web view)", publicId);
        QuotationResponseDto dto = quotationService.getPublicByPublicId(publicId);
        // Record the weblink view — async + best-effort, never affects this response.
        weblinkAnalyticsService.recordView(publicId, ClientIp.resolve(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success("Quotation fetched", dto));
    }

    @GetMapping("/{publicId}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable UUID publicId) {
        log.info("GET /api/public/quotations/{}/pdf (share link)", publicId);
        QuotationPdfResource pdf = quotationService.getPublicPdf(publicId);
        if (pdf.isRemote()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(pdf.url()))
                    .build();
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=quotation-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.content());
    }
}