package com.crm.travelcrm.leadsource.mapper;

import com.crm.travelcrm.leadsource.dto.ChannelCatalogDto;
import com.crm.travelcrm.leadsource.dto.IngestEventDto;
import com.crm.travelcrm.leadsource.dto.IntegrationResponseDto;
import com.crm.travelcrm.leadsource.entity.LeadIngestEvent;
import com.crm.travelcrm.leadsource.entity.LeadSourceIntegration;
import com.crm.travelcrm.leadsource.repository.LeadIngestEventView;
import com.crm.travelcrm.leadsource.spi.ChannelCatalogEntry;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-written mapper for the lead-source integration API.
 *
 * <p>Hand-written rather than MapStruct on purpose: the single most important property of
 * {@link IntegrationResponseDto} is that {@code credentials_enc} <b>never appears on it</b>, and a
 * generated mapper that helpfully auto-maps a same-named field is exactly how that would stop being
 * true. There is nothing to generate here anyway — every field is a decision.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LeadSourceIntegrationMapper {

    private final ObjectMapper objectMapper;

    /**
     * @param ingestUrl the full URL including the token — pass it ONLY on create and rotate, where the
     *                  token is in hand. It cannot be reconstructed later: only its hash is stored.
     * @param credentialKeysSet which secret keys are populated (never their values)
     */
    public IntegrationResponseDto toResponse(LeadSourceIntegration row, String ingestUrl,
                                             List<String> credentialKeysSet) {
        return new IntegrationResponseDto(
                row.getPublicId(),                       // never the internal Long id
                row.getChannel(),
                channelCode(row.getChannel()),
                row.getLabel(),
                row.isEnabled(),
                row.getStatus(),
                row.getResolutionMode(),
                row.getExternalAccountId(),
                ingestUrl,
                row.getIngestTokenPrefix(),
                row.getTokenRotatedAt(),
                row.getTokenPreviousRevokeAt(),
                row.getTokenLastUsedAt(),
                row.getCredentialsExpireAt(),
                row.getLeadCount(),
                row.getLastLeadReceivedAt(),
                readConfig(row),
                credentialKeysSet == null ? List.of() : credentialKeysSet,
                row.getCreatedAt());
        // NOTE: there is deliberately no credentials field. See IntegrationResponseDto's javadoc.
    }

    /**
     * A delivery for the history list.
     *
     * @param leadPublicId resolved by the caller in one batch lookup — the event carries only a logical
     *                     {@code lead_id}, and resolving it per-row would be an N+1 across the page
     */
    public IngestEventDto toEventDto(LeadIngestEventView view, UUID leadPublicId) {
        return new IngestEventDto(
                view.getPublicId(),
                view.getChannel(),
                view.getStatus(),
                view.getExternalEventId(),
                view.getDedupKey(),
                leadPublicId,
                view.getAttemptCount(),
                view.getNextRetryAt(),
                view.getErrorMessage(),
                view.isPayloadTruncated(),
                null,                          // the list never carries the body — see IngestEventDto
                view.getCreatedAt());
    }

    /** One delivery in full, body included — the only place {@code raw_payload} crosses the boundary. */
    public IngestEventDto toEventDto(LeadIngestEvent row, UUID leadPublicId) {
        return new IngestEventDto(
                row.getPublicId(),
                row.getChannel(),
                row.getStatus(),
                row.getExternalEventId(),
                row.getDedupKey(),
                leadPublicId,
                row.getAttemptCount(),
                row.getNextRetryAt(),
                row.getErrorMessage(),
                row.isPayloadTruncated(),
                row.getRawPayload(),
                row.getCreatedAt());
    }

    public ChannelCatalogDto toCatalogDto(LeadSourceChannel channel, ChannelCatalogEntry entry,
                                          List<LeadSourceIntegration> connections) {
        LocalDateTime lastLead = connections.stream()
                .map(LeadSourceIntegration::getLastLeadReceivedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new ChannelCatalogDto(
                channel.name(),
                channel.slug(),
                entry.displayName(),
                entry.description(),
                entry.setupHint(),
                entry.webhookUrlShown(),
                entry.credentialFields().stream()
                        .map(f -> new ChannelCatalogDto.FieldDto(
                                f.key(), f.label(), f.required(), f.helpText()))
                        .toList(),
                entry.configFields().stream()
                        .map(f -> new ChannelCatalogDto.FieldDto(
                                f.key(), f.label(), f.required(), f.helpText()))
                        .toList(),
                connections.size(),
                worstStatus(connections),
                connections.stream().mapToLong(LeadSourceIntegration::getLeadCount).sum(),
                lastLead == null ? null : lastLead.toString());
    }

    /**
     * The LEAST healthy status across a channel's connections.
     *
     * <p>Worst-wins, not first-wins or newest-wins: a grid card reading CONNECTED while one of its four
     * connections is silently broken is precisely the failure the status exists to surface.
     */
    private String worstStatus(List<LeadSourceIntegration> connections) {
        if (connections.isEmpty()) {
            return null;               // nothing connected yet — the card shows "Connect"
        }
        if (connections.stream().anyMatch(c -> "DEGRADED".equals(c.getStatus()))) {
            return "DEGRADED";
        }
        if (connections.stream().anyMatch(c -> !c.isEnabled() || "DISABLED".equals(c.getStatus()))) {
            return "DISABLED";
        }
        return "CONNECTED";
    }

    /** The non-secret blob, returnable wholesale — which is the entire reason it is a separate column. */
    private Map<String, String> readConfig(LeadSourceIntegration row) {
        if (row.getConfigJson() == null || row.getConfigJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.getConfigJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Integration {} has unreadable config_json; returning empty.", row.getId());
            return Map.of();
        }
    }

    private String channelCode(String slug) {
        return LeadSourceChannel.fromSlug(slug).map(Enum::name).orElse(slug);
    }
}
