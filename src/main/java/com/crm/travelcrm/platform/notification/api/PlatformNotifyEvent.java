package com.crm.travelcrm.platform.notification.api;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * The only class a platform module needs to import to notify the SuperAdmin.
 * Publish via {@code ApplicationEventPublisher.publishEvent(event)}.
 *
 * <p><b>No recipient field.</b> Unlike the tenant module's {@code NotifyEvent}, publishers do not
 * choose an audience: every platform notification goes to every active SuperAdmin, resolved by
 * {@code PlatformNotifyEventListener}. This is not a simplification for its own sake — the highest
 * value publisher is the Razorpay webhook, which runs with no principal and no context at all, so a
 * recipient it could name would have to be invented.
 *
 * <p><b>Carry your ids explicitly.</b> For the same reason, nothing here is read from
 * {@code TenantContext} or {@code PlatformContext}. The event must be self-contained.
 *
 * <p>Example:
 * <pre>{@code
 * applicationEventPublisher.publishEvent(
 *     PlatformNotifyEvent.builder()
 *         .type(PlatformNotificationType.SAAS_PAYMENT_RECEIVED)
 *         .title("Payment received")
 *         .message("Acme Travels paid ₹49,999 for invoice INV-2026-0042")
 *         .referenceType(PlatformNotificationType.REF_BILLING)
 *         .referencePublicId(txn.getPublicId())
 *         .tenantId(txn.getTenantId())
 *         .tenantName(txn.getTenantName())
 *         .build());
 * }</pre>
 */
@Getter
@Builder
public class PlatformNotifyEvent {

    /** Free-form string — use a {@link PlatformNotificationType} constant. */
    private final String type;

    private final String title;
    private final String message;

    /** The entity type that triggered this event, e.g. "BILLING". Free-form; stored verbatim. */
    private final String referenceType;

    /** Public UUID of the source entity — safe to expose in responses. */
    private final UUID referencePublicId;

    // ── Tenant context: which tenant is this ABOUT. All optional; none of it scopes anything. ──

    private final Long tenantId;
    private final String tenantName;
    private final UUID tenantPublicId;
}