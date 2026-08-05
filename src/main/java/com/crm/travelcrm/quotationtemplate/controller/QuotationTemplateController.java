package com.crm.travelcrm.quotationtemplate.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotationtemplate.dto.ApplyTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateResponse;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplatePreview;
import com.crm.travelcrm.quotationtemplate.dto.SaveAsTemplateRequest;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchRequest;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchResponse;
import com.crm.travelcrm.quotationtemplate.service.QuotationTemplateService;
import com.crm.travelcrm.quotationtemplate.service.TemplateMatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Package templates. Reuses the {@code QUOTATION_*} permissions rather than inventing its own — a
 * template is only ever a seed for a quotation, and anyone who may create a quotation may create the
 * blueprint for one. The path is separately gated on the QUOTATIONS module in
 * {@code ModuleAccessFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/quotation-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('QUOTATION_READ')")   // class default; mutating methods override below
public class QuotationTemplateController {

    private final QuotationTemplateService templateService;
    private final TemplateMatchService matchService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAuthority('QUOTATION_CREATE')")
    public ResponseEntity<ApiResponse<QuotationTemplateResponse>> create(
            @Valid @RequestBody QuotationTemplateRequest request) {
        log.info("POST /api/quotation-templates | name: {}", request.getName());
        QuotationTemplateResponse response = templateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Template created successfully", response, 201));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('QUOTATION_UPDATE')")
    public ResponseEntity<ApiResponse<QuotationTemplateResponse>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody QuotationTemplateRequest request) {
        log.info("PUT /api/quotation-templates/{}", publicId);
        return ResponseEntity.ok(ApiResponse.success(
                "Template updated successfully", templateService.update(publicId, request)));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<QuotationTemplateResponse>> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Template fetched successfully", templateService.getByPublicId(publicId)));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<QuotationTemplateResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<QuotationTemplateResponse> result =
                templateService.search(keyword, active, page, size, sortBy, sortDir);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Templates fetched successfully",
                result.getContent(),
                PaginationMeta.from(result, sortBy, sortDir)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('QUOTATION_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        log.info("DELETE /api/quotation-templates/{}", publicId);
        templateService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Template deleted successfully"));
    }

    // ── Match ─────────────────────────────────────────────────────────────────

    /**
     * POST rather than GET: the agent's overrides (budget, star tier, month) are a body, and the
     * result is a ranking, not an addressable resource.
     */
    @PostMapping("/match")
    public ResponseEntity<ApiResponse<List<TemplateMatchResponse>>> match(
            @Valid @RequestBody TemplateMatchRequest request) {
        log.info("POST /api/quotation-templates/match | lead: {}", request.getLeadId());
        return ResponseEntity.ok(ApiResponse.success(
                "Matching templates fetched successfully", matchService.match(request)));
    }

    // ── Save as Template (quotation → template) ───────────────────────────────

    /**
     * What capturing this quotation would produce — derived name, nights, tier, price, the resolved
     * cities, and the sections a template cannot hold. Writes nothing.
     *
     * <p>A separate call rather than fields on the save request because the modal must be able to
     * show the losses <i>before</i> the agent commits. Computing them server-side is what stops the
     * dialog and the actual behaviour from drifting apart.
     */
    @GetMapping("/from-quotation/{quotationPublicId}/preview")
    public ResponseEntity<ApiResponse<SaveAsTemplatePreview>> previewFromQuotation(
            @PathVariable UUID quotationPublicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Template preview generated successfully",
                templateService.previewFromQuotation(quotationPublicId)));
    }

    /** Capture a saved quotation as a package template, or overwrite an existing one. */
    @PostMapping("/from-quotation")
    @PreAuthorize("hasAuthority('QUOTATION_CREATE')")
    public ResponseEntity<ApiResponse<QuotationTemplateResponse>> saveFromQuotation(
            @Valid @RequestBody SaveAsTemplateRequest request) {
        log.info("POST /api/quotation-templates/from-quotation | quotation: {} | update: {}",
                request.getQuotationId(), request.getUpdateTemplateId());
        QuotationTemplateResponse response = templateService.saveFromQuotation(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Template saved successfully", response, 201));
    }

    // ── Apply (clone → edit) ──────────────────────────────────────────────────

    /** Returns the freshly created DRAFT quotation; the client navigates straight into its builder. */
    @PostMapping("/{publicId}/apply")
    @PreAuthorize("hasAuthority('QUOTATION_CREATE')")
    public ResponseEntity<ApiResponse<QuotationResponseDto>> apply(
            @PathVariable UUID publicId,
            @Valid @RequestBody ApplyTemplateRequest request) {
        log.info("POST /api/quotation-templates/{}/apply | lead: {}", publicId, request.getLeadId());
        QuotationResponseDto quotation = templateService.apply(publicId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Template applied successfully", quotation, 201));
    }
}