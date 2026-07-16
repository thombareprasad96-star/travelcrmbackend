package com.crm.travelcrm.marketing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.CampaignRecipientResponse;
import com.crm.travelcrm.marketing.dto.CampaignResponse;
import com.crm.travelcrm.marketing.dto.CampaignSummaryResponse;
import com.crm.travelcrm.marketing.dto.CreateCampaignRequest;
import com.crm.travelcrm.marketing.dto.SendTestRequest;
import com.crm.travelcrm.marketing.dto.UpdateCampaignRequest;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;
import com.crm.travelcrm.marketing.service.CampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Bulk broadcast campaigns (WhatsApp / email). Class default is {@code MARKETING_READ};
 * mutating routes override to CREATE/UPDATE/DELETE and the lifecycle actions
 * (send/cancel/test) require {@code MARKETING_SEND}. Actual delivery is asynchronous —
 * {@code /send} either schedules or marks the campaign due, then {@code CampaignDispatchScheduler}
 * materialises recipients and sends in throttled batches.
 */
@RestController
@RequestMapping("/api/marketing/campaigns")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MARKETING_READ')")   // class default; mutating methods override below
public class CampaignController {

    private final CampaignService campaignService;

    /** Dashboard tiles. Literal path — declared before /{publicId}. */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<CampaignSummaryResponse>> summary() {
        return ResponseEntity.ok(campaignService.summary());
    }

    @GetMapping
    public PagedApiResponse<CampaignResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(required = false) MarketingChannel channel) {
        return campaignService.list(page, size, sortBy, sortDir, search, status, channel);
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<CampaignResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(campaignService.get(publicId));
    }

    @GetMapping("/{publicId}/recipients")
    public PagedApiResponse<CampaignRecipientResponse> recipients(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RecipientStatus status) {
        return campaignService.recipients(publicId, page, size, status);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MARKETING_CREATE')")
    public ResponseEntity<ApiResponse<CampaignResponse>> create(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.create(request));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_UPDATE')")
    public ResponseEntity<ApiResponse<CampaignResponse>> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateCampaignRequest request) {
        return ResponseEntity.ok(campaignService.update(publicId, request));
    }

    @PostMapping("/{publicId}/send")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<CampaignResponse>> send(@PathVariable UUID publicId) {
        return ResponseEntity.ok(campaignService.send(publicId));
    }

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<CampaignResponse>> cancel(@PathVariable UUID publicId) {
        return ResponseEntity.ok(campaignService.cancel(publicId));
    }

    @PostMapping("/{publicId}/test")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<Void>> test(
            @PathVariable UUID publicId, @Valid @RequestBody SendTestRequest request) {
        return ResponseEntity.ok(campaignService.test(publicId, request));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('MARKETING_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(campaignService.delete(publicId));
    }
}