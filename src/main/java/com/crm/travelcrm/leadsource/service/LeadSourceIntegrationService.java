package com.crm.travelcrm.leadsource.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.leadsource.dto.ChannelCatalogDto;
import com.crm.travelcrm.leadsource.dto.IngestEventDto;
import com.crm.travelcrm.leadsource.dto.IntegrationResponseDto;
import com.crm.travelcrm.leadsource.dto.SaveIntegrationRequestDto;
import com.crm.travelcrm.leadsource.entity.LeadIngestEvent;
import com.crm.travelcrm.leadsource.entity.LeadSourceIntegration;
import com.crm.travelcrm.leadsource.mapper.LeadSourceIntegrationMapper;
import com.crm.travelcrm.leadsource.registry.LeadSourceRegistry;
import com.crm.travelcrm.leadsource.repository.LeadIngestEventRepository;
import com.crm.travelcrm.leadsource.repository.LeadIngestEventView;
import com.crm.travelcrm.leadsource.repository.LeadSourceIntegrationRepository;
import com.crm.travelcrm.leadsource.spi.ChannelCatalogEntry;
import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-facing management of lead-source connections.
 *
 * <p><b>This is the ONE chokepoint where credentials are encrypted.</b> {@code AesSecretCipher} is a
 * plain component with explicit {@code encrypt()}/{@code decrypt()} — there is no
 * {@code AttributeConverter} anywhere in this codebase — so {@code credentials_enc} silently stores
 * PLAINTEXT if any write path forgets the call. Every write to that column must go through here.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeadSourceIntegrationService {

    private final LeadSourceIntegrationRepository repository;
    private final LeadIngestEventRepository eventRepository;
    private final LeadRepository leadRepository;
    private final LeadSourceRegistry registry;
    private final IngestTokenService tokenService;
    private final LeadSourceIntegrationMapper mapper;
    private final AesSecretCipher cipher;
    private final ObjectMapper objectMapper;

    /**
     * The base URL the ingest URLs are built from. Must be the PUBLIC address a provider can reach —
     * a localhost default here means a tenant pastes a useless URL into JustDial's console and the
     * integration silently never fires.
     */
    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    /**
     * How long the previous token keeps working after a rotation.
     *
     * <p>Rotation MUST overlap: the tenant has to physically go and paste the new URL into the
     * provider's console, and without a window every lead in that human-sized gap is lost — silently,
     * because the provider just gets a 401 and stops.
     */
    @Value("${app.lead.token-rotation-overlap-hours:72}")
    private int rotationOverlapHours;

    // ── Catalog ───────────────────────────────────────────────────────────────

    /** Every channel this node can serve, with a summary of the tenant's connections to it. */
    @Transactional(readOnly = true)
    public List<ChannelCatalogDto> catalog() {
        Long tenantId = requireTenant();
        Map<String, List<LeadSourceIntegration>> byChannel = new HashMap<>();
        for (LeadSourceIntegration row : repository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)) {
            byChannel.computeIfAbsent(row.getChannel(), k -> new ArrayList<>()).add(row);
        }

        List<ChannelCatalogDto> out = new ArrayList<>();
        for (LeadSourceChannel channel : registry.deployedChannels()) {
            ChannelCatalogEntry entry = registry.adapterFor(channel).catalog();
            List<LeadSourceIntegration> rows = byChannel.getOrDefault(channel.slug(), List.of());
            out.add(mapper.toCatalogDto(channel, entry, rows));
        }
        return out;
    }

    // ── Connections ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<IntegrationResponseDto> list(String channelSlug) {
        Long tenantId = requireTenant();
        List<LeadSourceIntegration> rows = channelSlug == null
                ? repository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                : repository.findByTenantIdAndChannelAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, resolveChannel(channelSlug).slug());
        return rows.stream()
                .sorted(Comparator.comparing(LeadSourceIntegration::getCreatedAt).reversed())
                // No ingestUrl: only its hash is stored, so it genuinely cannot be shown again.
                .map(row -> mapper.toResponse(row, null, readCredentialKeys(row)))
                .toList();
    }

    @Transactional(readOnly = true)
    public IntegrationResponseDto get(UUID publicId) {
        LeadSourceIntegration row = findOrThrow(publicId);
        return mapper.toResponse(row, null, readCredentialKeys(row));
    }

    /**
     * Create a connection and mint its ingest token.
     *
     * <p>The returned {@code ingestUrl} is the <b>only</b> time the token is ever visible: only its
     * SHA-256 hash is stored. A tenant who loses it must rotate — which is a feature, not an
     * inconvenience.
     */
    @Transactional
    public IntegrationResponseDto create(SaveIntegrationRequestDto request) {
        Long tenantId = requireTenant();
        LeadSourceChannel channel = resolveChannel(request.getChannel());
        LeadSourceAdapter adapter = registry.adapterFor(channel);

        Map<String, String> credentials = request.getCredentials() == null
                ? Map.of() : new HashMap<>(request.getCredentials());
        assertRequiredCredentials(adapter, credentials);

        String token = tokenService.mint();

        LeadSourceIntegration row = LeadSourceIntegration.builder()
                .channel(channel.slug())
                .label(request.getLabel().trim())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .resolutionMode(adapter.resolution().name())
                .ingestTokenHash(tokenService.hash(token))
                .ingestTokenPrefix(tokenService.displayPrefix(token))
                .tokenRotatedAt(LocalDateTime.now())
                .externalAccountId(trimToNull(request.getExternalAccountId()))
                .credentialsEnc(encryptCredentials(credentials))
                .configJson(writeJson(request.getConfig()))
                .status("CONNECTED")
                .build();
        row.setTenantId(tenantId);

        LeadSourceIntegration saved = repository.save(row);
        log.info("Lead-source connection created | tenant: {} | channel: {} | label: {} | prefix: {}",
                tenantId, channel.slug(), saved.getLabel(), saved.getIngestTokenPrefix());

        return mapper.toResponse(saved, ingestUrl(channel, token), List.copyOf(credentials.keySet()));
    }

    @Transactional
    public IntegrationResponseDto update(UUID publicId, SaveIntegrationRequestDto request) {
        LeadSourceIntegration row = findOrThrow(publicId);
        // The channel is deliberately NOT updatable: changing it would repoint a live URL at a
        // different parser while the provider keeps posting the old shape.
        LeadSourceAdapter adapter = registry.adapterFor(resolveChannel(row.getChannel()));

        row.setLabel(request.getLabel().trim());
        if (request.getEnabled() != null) {
            row.setEnabled(request.getEnabled());
        }
        if (request.getExternalAccountId() != null) {
            row.setExternalAccountId(trimToNull(request.getExternalAccountId()));
        }
        if (request.getConfig() != null) {
            row.setConfigJson(writeJson(request.getConfig()));
        }

        // MERGE credentials rather than replace: the edit form never received the stored secrets (they
        // are write-only), so a wholesale replace would blank every one the user did not retype.
        Map<String, String> merged = decryptCredentials(row);
        if (request.getCredentials() != null) {
            request.getCredentials().forEach((k, v) -> {
                if (v == null || v.isBlank()) {
                    merged.remove(k);      // an explicitly-sent blank clears it
                } else {
                    merged.put(k, v);
                }
            });
            assertRequiredCredentials(adapter, merged);
            row.setCredentialsEnc(encryptCredentials(merged));
        }

        LeadSourceIntegration saved = repository.save(row);
        return mapper.toResponse(saved, null, List.copyOf(merged.keySet()));
    }

    /**
     * Rotate the ingest token, keeping the previous one alive for an overlap window.
     *
     * @return the connection, carrying the NEW url — shown once and never again
     */
    @Transactional
    public IntegrationResponseDto rotateToken(UUID publicId) {
        LeadSourceIntegration row = findOrThrow(publicId);
        LeadSourceChannel channel = resolveChannel(row.getChannel());

        String token = tokenService.mint();
        row.setIngestTokenHashPrevious(row.getIngestTokenHash());
        row.setTokenPreviousRevokeAt(LocalDateTime.now().plusHours(rotationOverlapHours));
        row.setIngestTokenHash(tokenService.hash(token));
        row.setIngestTokenPrefix(tokenService.displayPrefix(token));
        row.setTokenRotatedAt(LocalDateTime.now());

        LeadSourceIntegration saved = repository.save(row);
        log.info("Lead-source token ROTATED | tenant: {} | integration: {} | old token accepted until {}",
                saved.getTenantId(), saved.getId(), saved.getTokenPreviousRevokeAt());

        return mapper.toResponse(saved, ingestUrl(channel, token), readCredentialKeys(saved));
    }

    /** End the overlap window now — for a token believed leaked. */
    @Transactional
    public IntegrationResponseDto revokePreviousToken(UUID publicId) {
        LeadSourceIntegration row = findOrThrow(publicId);
        row.setIngestTokenHashPrevious(null);
        row.setTokenPreviousRevokeAt(null);
        return mapper.toResponse(repository.save(row), null, readCredentialKeys(row));
    }

    // ── Delivery history ──────────────────────────────────────────────────────

    /**
     * What actually arrived on this connection, newest first.
     *
     * <p>Scoped by BOTH the resolved connection and the tenant: {@code findOrThrow} already 404s a
     * foreign {@code publicId}, so a cross-tenant probe never reaches the query.
     *
     * <p>The rows carry no body — see {@link LeadIngestEventView}. Use {@link #event} for one.
     */
    @Transactional(readOnly = true)
    public Page<IngestEventDto> events(UUID publicId, Pageable pageable) {
        Long tenantId = requireTenant();
        LeadSourceIntegration connection = findOrThrow(publicId);

        Page<LeadIngestEventView> page = eventRepository
                .findByTenantIdAndIntegrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        tenantId, connection.getId(), pageable);

        Map<Long, UUID> leadPublicIds = resolveLeadPublicIds(tenantId, page.getContent().stream()
                .map(LeadIngestEventView::getLeadId)
                .filter(java.util.Objects::nonNull)
                .toList());

        return page.map(view -> mapper.toEventDto(view, lookupLead(leadPublicIds, view.getLeadId())));
    }

    /** One delivery in full, including the (already secret-redacted) body a FAILED row needs to be debuggable. */
    @Transactional(readOnly = true)
    public IngestEventDto event(UUID publicId, UUID eventPublicId) {
        Long tenantId = requireTenant();
        LeadSourceIntegration connection = findOrThrow(publicId);

        LeadIngestEvent event = eventRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(eventPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found: " + eventPublicId));

        // A delivery that belongs to another of this tenant's connections is a 404 here, not a 200:
        // the URL asserts it is this connection's, and answering anyway would make the nesting a lie.
        if (!java.util.Objects.equals(event.getIntegrationId(), connection.getId())) {
            throw new ResourceNotFoundException("Delivery not found: " + eventPublicId);
        }

        Map<Long, UUID> leadPublicIds = resolveLeadPublicIds(tenantId,
                event.getLeadId() == null ? List.of() : List.of(event.getLeadId()));
        return mapper.toEventDto(event, lookupLead(leadPublicIds, event.getLeadId()));
    }

    /**
     * Resolve {@code lead_id} → {@code publicId} for a whole page in ONE query.
     *
     * <p>The internal Long never crosses the API boundary, but the event stores only that — so the
     * translation has to happen somewhere, and per-row would be an N+1. A trashed lead simply drops out
     * ({@code deletedAtIsNull}), leaving a null link, which is honest: the lead is gone but its delivery
     * record deliberately outlives it.
     */
    private Map<Long, UUID> resolveLeadPublicIds(Long tenantId, List<Long> leadIds) {
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UUID> out = new HashMap<>();
        for (Lead lead : leadRepository.findByTenantIdAndIdInAndDeletedAtIsNull(tenantId, Set.copyOf(leadIds))) {
            out.put(lead.getId(), lead.getPublicId());
        }
        return out;
    }

    /** Null-safe: {@code Map.of().get(null)} throws, and a null leadId is the common case. */
    private UUID lookupLead(Map<Long, UUID> leadPublicIds, Long leadId) {
        return leadId == null ? null : leadPublicIds.get(leadId);
    }

    @Transactional
    public void delete(UUID publicId) {
        LeadSourceIntegration row = findOrThrow(publicId);
        // Soft delete: every resolver finder carries deletedAtIsNull, so the token stops working
        // immediately, while the delivery history stays readable.
        row.softDelete(null);
        repository.save(row);
        log.info("Lead-source connection deleted | tenant: {} | integration: {}",
                row.getTenantId(), row.getId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Fail at SAVE time on a missing credential, rather than at ingest.
     *
     * <p>This is the credential bag's ONLY mitigation: the blob is encrypted, so the database cannot
     * validate it. Without this check a typo'd or missing key surfaces as a lost lead weeks later,
     * instead of as a form error now.
     */
    private void assertRequiredCredentials(LeadSourceAdapter adapter, Map<String, String> credentials) {
        List<String> missing = adapter.catalog().requiredCredentialKeys().stream()
                .filter(key -> {
                    String v = credentials.get(key);
                    return v == null || v.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    "This connection needs: " + String.join(", ", missing), HttpStatus.BAD_REQUEST);
        }
    }

    private String ingestUrl(LeadSourceChannel channel, String token) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/api/webhooks/leads/" + channel.slug() + "/" + token;
    }

    private LeadSourceChannel resolveChannel(String codeOrSlug) {
        if (codeOrSlug == null || codeOrSlug.isBlank()) {
            throw new BusinessException("Channel is required", HttpStatus.BAD_REQUEST);
        }
        String needle = codeOrSlug.trim();
        // Accept both the enum name (what the frontend holds) and the slug (what the URL holds).
        return LeadSourceChannel.fromSlug(needle)
                .or(() -> java.util.Arrays.stream(LeadSourceChannel.values())
                        .filter(c -> c.name().equalsIgnoreCase(needle))
                        .findFirst())
                .filter(c -> registry.findAdapter(c).isPresent())
                .orElseThrow(() -> new BusinessException(
                        "Unknown or unavailable channel: " + codeOrSlug, HttpStatus.BAD_REQUEST));
    }

    private LeadSourceIntegration findOrThrow(UUID publicId) {
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenant())
                .orElseThrow(() -> new ResourceNotFoundException("Connection not found: " + publicId));
    }

    private String encryptCredentials(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        return cipher.encrypt(writeJson(credentials));
    }

    private Map<String, String> decryptCredentials(LeadSourceIntegration row) {
        if (row.getCredentialsEnc() == null || row.getCredentialsEnc().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(cipher.decrypt(row.getCredentialsEnc()),
                    new TypeReference<HashMap<String, String>>() {});
        } catch (Exception e) {
            log.error("Could not decrypt credentials for integration {}: {}",
                    row.getId(), e.getClass().getSimpleName());
            return new HashMap<>();
        }
    }

    /** Which secret keys are set — so the form can show "configured" without the value leaving the server. */
    private List<String> readCredentialKeys(LeadSourceIntegration row) {
        return List.copyOf(decryptCredentials(row).keySet());
    }

    private String writeJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BusinessException("Could not serialise settings", HttpStatus.BAD_REQUEST);
        }
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty for lead-source integration management.");
        }
        return tenantId;
    }

    /** Lower-cased slug form, for callers holding a channel code. */
    static String slugOf(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
