package com.crm.travelcrm.leadsource.repository;

import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A delivery as the history list needs it — <b>everything except {@code raw_payload}</b>.
 *
 * <p>This is a closed projection, so Hibernate selects only these columns. That is the whole point:
 * {@code raw_payload} is a TEXT column capped at 64KB, and a 20-row page of full entities would drag
 * up to 1.2MB out of the database to render a list that never shows a byte of it. The body is fetched
 * one delivery at a time, on demand.
 *
 * <p>Same reasoning as the traveler portal's document list, which projects precisely so the blobs stay
 * in Postgres.
 */
public interface LeadIngestEventView {

    UUID getPublicId();

    String getChannel();

    LeadIngestStatus getStatus();

    String getExternalEventId();

    String getDedupKey();

    /** Logical FK to {@code leads.id}. Null unless the delivery actually produced or touched a lead. */
    Long getLeadId();

    int getAttemptCount();

    LocalDateTime getNextRetryAt();

    String getErrorMessage();

    /** True when the stored body was cut at the 64KB cap — surfaced so nobody debugs a silent truncation. */
    boolean isPayloadTruncated();

    LocalDateTime getCreatedAt();
}
