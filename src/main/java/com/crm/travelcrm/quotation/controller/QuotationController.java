package com.crm.travelcrm.quotation.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.quotation.dto.QuotationEmailRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationPdfResource;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.service.QuotationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class QuotationController {

    private final QuotationService quotationService;

    // ── Create ──────────────────────────────────────────────────────────────--
    @PostMapping
    public ResponseEntity<ApiResponse<QuotationResponseDto>> create(
            @Valid @RequestBody QuotationRequestDto request) {
        log.info("POST /api/quotations | lead: {}", request.getLeadId());
        QuotationResponseDto response = quotationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Quotation created successfully", response, 201));
    }

    // ── Update ──────────────────────────────────────────────────────────────--
    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody QuotationRequestDto request) {
        log.info("PUT /api/quotations/{}", publicId);
        QuotationResponseDto response = quotationService.update(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Quotation updated successfully", response));
    }

    // ── Get one ─────────────────────────────────────────────────────────────--
    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> getById(@PathVariable UUID publicId) {
        QuotationResponseDto response = quotationService.getByPublicId(publicId);
        return ResponseEntity.ok(ApiResponse.success("Quotation fetched successfully", response));
    }

    // ── List (paged, optional search/filter) ──────────────────────────────────
    @GetMapping
    public ResponseEntity<PagedApiResponse<QuotationSummaryDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) UUID leadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        QuotationStage stageEnum = (stage == null || stage.isBlank()) ? null : QuotationStage.fromValue(stage);
        Page<QuotationSummaryDto> result =
                quotationService.search(search, stageEnum, leadId, page, size, sortBy, sortDir);

        return ResponseEntity.ok(PagedApiResponse.of(
                "Quotations fetched successfully",
                result.getContent(),
                PaginationMeta.from(result, sortBy, sortDir)));
    }

    // ── List by lead ───────────────────────────────────────────────────────--
    @GetMapping("/lead/{leadId}")
    public ResponseEntity<ApiResponse<List<QuotationSummaryDto>>> getByLead(@PathVariable UUID leadId) {
        List<QuotationSummaryDto> result = quotationService.getByLead(leadId);
        return ResponseEntity.ok(ApiResponse.success("Quotations fetched successfully", result));
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────
    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        log.info("DELETE /api/quotations/{}", publicId);
        quotationService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Quotation deleted successfully"));
    }

    // ── Stage change ────────────────────────────────────────────────────────--
    @PatchMapping("/{publicId}/stage")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> updateStage(
            @PathVariable UUID publicId,
            @RequestParam String stage) {
        log.info("PATCH /api/quotations/{}/stage -> {}", publicId, stage);
        QuotationResponseDto response = quotationService.updateStage(publicId, QuotationStage.fromValue(stage));
        return ResponseEntity.ok(ApiResponse.success("Quotation stage updated successfully", response));
    }

    // ── Duplicate ─────────────────────────────────────────────────────────────
    @PostMapping("/{publicId}/duplicate")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> duplicate(@PathVariable UUID publicId) {
        log.info("POST /api/quotations/{}/duplicate", publicId);
        QuotationResponseDto response = quotationService.duplicate(publicId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Quotation duplicated successfully", response, 201));
    }

    // ── New version ───────────────────────────────────────────────────────────
    // Copies the quotation (+ all items), increments the version (status -> DRAFT),
    // renders the PDF and stores it on Cloudinary.
    @PostMapping("/{publicId}/new-version")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> newVersion(@PathVariable UUID publicId) {
        log.info("POST /api/quotations/{}/new-version", publicId);
        QuotationResponseDto response = quotationService.newVersion(publicId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("New quotation version created", response, 201));
    }

    // ── PDF ─────────────────────────────────────────────────────────────────--
    // Returns the stored Cloudinary PDF (302 redirect) if one exists, otherwise
    // renders it on-the-fly and streams the bytes.
    @GetMapping("/{publicId}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable UUID publicId) {
        log.info("GET /api/quotations/{}/pdf", publicId);
        QuotationPdfResource pdf = quotationService.getPdf(publicId);
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

    // ── Send email ────────────────────────────────────────────────────────────
    @PostMapping("/{publicId}/send-email")
    public ResponseEntity<ApiResponse<Void>> sendEmail(
            @PathVariable UUID publicId,
            @Valid @RequestBody QuotationEmailRequestDto request) {
        log.info("POST /api/quotations/{}/send-email -> {}", publicId, request.getToEmail());
        quotationService.sendEmail(publicId, request);
        return ResponseEntity.ok(ApiResponse.success("Quotation emailed successfully"));
    }

    // ── Share link ──────────────────────────────────────────────────────────--
    @GetMapping("/{publicId}/share-link")
    public ResponseEntity<ApiResponse<Map<String, String>>> shareLink(@PathVariable UUID publicId) {
        String url = quotationService.getShareLink(publicId);
        return ResponseEntity.ok(ApiResponse.success("Share link generated", Map.of("shareUrl", url)));
    }
}