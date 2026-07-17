package com.crm.travelcrm.leadsource.web;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.leadsource.dto.ChannelCatalogDto;
import com.crm.travelcrm.leadsource.dto.IngestEventDto;
import com.crm.travelcrm.leadsource.dto.IntegrationResponseDto;
import com.crm.travelcrm.leadsource.dto.SaveIntegrationRequestDto;
import com.crm.travelcrm.leadsource.service.LeadSourceIntegrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-facing management of lead-source connections.
 *
 * <p>Entirely separate from {@link LeadIngestWebhookController}: that one is public, unauthenticated,
 * token-resolved and envelope-free; this one is a normal authenticated CRM API. Sharing a controller
 * between the two would put a {@code permitAll} path one annotation slip away from an authenticated
 * one.
 *
 * <p>Gated on {@code SETTINGS_MANAGE} — connecting a lead source means handing over credentials and
 * minting a URL that can create leads, which is an admin action, not a lead-desk one.
 */
@RestController
@RequestMapping("/api/lead-sources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
public class LeadSourceIntegrationController {

    private final LeadSourceIntegrationService service;

    /** Every channel this deployment can serve, summarising the tenant's connections to each. */
    @GetMapping("/channels")
    public ResponseEntity<ApiResponse<List<ChannelCatalogDto>>> channels() {
        return ResponseEntity.ok(ApiResponse.success("Channels fetched", service.catalog()));
    }

    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<List<IntegrationResponseDto>>> list(
            @RequestParam(required = false) String channel) {
        return ResponseEntity.ok(ApiResponse.success("Connections fetched", service.list(channel)));
    }

    @GetMapping("/connections/{publicId}")
    public ResponseEntity<ApiResponse<IntegrationResponseDto>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Connection fetched", service.get(publicId)));
    }

    /**
     * Create a connection.
     *
     * <p>The response carries {@code ingestUrl} — <b>the only time the token is ever visible</b>, since
     * only its hash is stored. The frontend must make the user copy it before leaving the screen.
     */
    @PostMapping("/connections")
    public ResponseEntity<ApiResponse<IntegrationResponseDto>> create(
            @Valid @RequestBody SaveIntegrationRequestDto request) {
        IntegrationResponseDto created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Connection created", created, 201));
    }

    @PutMapping("/connections/{publicId}")
    public ResponseEntity<ApiResponse<IntegrationResponseDto>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody SaveIntegrationRequestDto request) {
        return ResponseEntity.ok(
                ApiResponse.success("Connection updated", service.update(publicId, request)));
    }

    /**
     * Mint a new token, keeping the old one working for an overlap window.
     *
     * <p>A POST, not a PUT: it is not idempotent — calling it twice invalidates the token issued by the
     * first call, which for a tenant mid-repaste is a real, painful difference.
     */
    @PostMapping("/connections/{publicId}/rotate-token")
    public ResponseEntity<ApiResponse<IntegrationResponseDto>> rotateToken(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "New URL issued. Paste it into the provider before the old one expires.",
                service.rotateToken(publicId)));
    }

    /** End the overlap window immediately — for a token believed leaked. */
    @PostMapping("/connections/{publicId}/revoke-previous-token")
    public ResponseEntity<ApiResponse<IntegrationResponseDto>> revokePrevious(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "The previous URL no longer works", service.revokePreviousToken(publicId)));
    }

    @DeleteMapping("/connections/{publicId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        service.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Connection deleted"));
    }

    /**
     * What actually arrived on this connection — the "recent deliveries" panel.
     *
     * <p>Rows carry no payload body: the list projects around a 64KB TEXT column. Fetch one delivery to
     * read what the provider sent.
     */
    @GetMapping("/connections/{publicId}/events")
    public ResponseEntity<PagedApiResponse<IngestEventDto>> events(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<IngestEventDto> result = service.events(publicId, pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Deliveries fetched", result.getContent(), PaginationMeta.from(result)));
    }

    /** One delivery in full, body included — what makes a FAILED row actionable rather than just red. */
    @GetMapping("/connections/{publicId}/events/{eventPublicId}")
    public ResponseEntity<ApiResponse<IngestEventDto>> event(
            @PathVariable UUID publicId,
            @PathVariable UUID eventPublicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Delivery fetched", service.event(publicId, eventPublicId)));
    }
}
