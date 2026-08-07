package com.crm.travelcrm.leadsource.dto;

import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One inbound delivery, as the tenant sees it — the "recent deliveries" panel and its detail view.
 *
 * <p>This is the payoff for logging a delivery BEFORE verifying it: a rejected enquiry is a row the
 * tenant can read, rather than the silence that made dropped enquiries invisible before.
 *
 * @param leadPublicId  the lead this delivery produced, or null — either because it produced none
 *                      (IGNORED / DUPLICATE / FAILED / any QUARANTINED_*) or because the lead has since
 *                      been trashed. An ingest event deliberately outlives its lead, so this link is
 *                      genuinely optional; a null here is history, not an error.
 * @param rawPayload    the delivery body, secret-fields already redacted at write time. <b>Null in the
 *                      list</b> — it is only read on the single-delivery fetch, since the list projects
 *                      around the column. Present means "this is what the provider actually sent",
 *                      which is the only thing that makes a FAILED row actionable.
 * @param payloadTruncated true when the body was cut at the 64KB cap — so a truncation is never mistaken
 *                      for a malformed provider payload.
 */
public record IngestEventDto(
        UUID publicId,
        String channel,
        LeadIngestStatus status,
        String externalEventId,
        String dedupKey,
        UUID leadPublicId,
        int attemptCount,
        LocalDateTime nextRetryAt,
        String errorMessage,
        boolean payloadTruncated,
        String rawPayload,
        LocalDateTime createdAt
) {}
