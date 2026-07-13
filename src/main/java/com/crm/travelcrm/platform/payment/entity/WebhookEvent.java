package com.crm.travelcrm.platform.payment.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Idempotency ledger + audit for inbound gateway webhooks. {@code eventId} carries a UNIQUE
 * constraint: the row is inserted before processing, so a retried/duplicate delivery of the same
 * event collides on insert and is skipped — the gateway may deliver the same event more than once.
 * Every genuinely-processed event leaves exactly one durable row here.
 */
@Entity
@Table(name = "payment_webhook_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_webhook_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_webhook_type", columnList = "event_type"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class WebhookEvent extends BaseEntity {

    /** Gateway event id (Razorpay {@code X-Razorpay-Event-Id}), or a body hash when the header is absent. */
    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "provider", length = 20)
    @Builder.Default
    private String provider = "RAZORPAY";

    /** Event name, e.g. {@code payment.captured}. */
    @Column(name = "event_type", length = 60)
    private String eventType;

    /** Gateway order/subscription id extracted from the payload, for cross-referencing. */
    @Column(name = "related_ref", length = 64)
    private String relatedRef;

    @Column(name = "processed", nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(name = "error_message", length = 500)
    private String errorMessage;
}