package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;

import java.util.List;
import java.util.UUID;

/**
 * What one delivery produced — <b>returned</b> from the transactional ingest so the non-transactional
 * gateway can publish notifications AFTER commit.
 *
 * <p><b>Why the notification is data here instead of a publish inside the transaction.</b> The rule
 * "publish nothing from inside a transaction" is real, but the folklore reason for it is WRONG, and
 * being wrong here is not harmless: clearing {@code TenantContext} does NOT disable an already-enabled
 * Hibernate filter — {@code TenantFilterAspect} binds the value when it enables the filter and never
 * re-reads the ThreadLocal. A contributor who tests the folklore version (publish mid-method, query
 * after, observe correct scoping) concludes the rule is superstition and drops it.
 *
 * <p>The TRUE mechanism: {@code NotifyEventListener} is a plain synchronous {@code @EventListener}
 * that calls {@code TenantContext.clear()} in its {@code finally}, on the PUBLISHER'S OWN THREAD, with
 * no save/restore. So after any publish:
 * <ul>
 *   <li>any {@code BaseTenantEntity} persisted without an explicit tenantId throws in
 *       {@code TenantEntityListener}, which <b>swallows it into an ERROR log</b> — the notification or
 *       the LeadLog vanishes silently;</li>
 *   <li>any subsequent cross-bean {@code REQUIRES_NEW} call re-enters the aspect with a null context
 *       and <b>genuinely does span all tenants</b>.</li>
 * </ul>
 *
 * <p>Publishing after commit also fixes, for free, the "SSE push fires before commit" problem:
 * {@code InAppNotificationChannel} joins the caller's transaction, so today the browser is told about
 * a lead before it exists.
 *
 * @param status         the terminal status to stamp on the ingest event
 * @param leadId         internal id of the lead created or appended to; null when nothing was written
 * @param leadPublicId   for the notification's deep link
 * @param notifyType     {@code LEAD_CREATED} or {@code LEAD_ACTIVITY_APPENDED}; null ⇒ notify nobody
 * @param recipientUserIds resolved INSIDE the transaction (it needs a tenant-scoped read) but published
 *                       outside it. <b>Set explicitly</b> — the implicit fallback resolves TENANT_ADMIN
 *                       only and silently drops every MANAGER.
 * @param title          pre-rendered, because the entity is not available after commit
 * @param message        pre-rendered, same reason
 */
public record LeadIngestOutcome(
        LeadIngestStatus status,
        Long leadId,
        UUID leadPublicId,
        String notifyType,
        List<Long> recipientUserIds,
        String title,
        String message
) {

    public LeadIngestOutcome {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }

    /** A delivery that wrote nothing (ignored ping, duplicate, quota quarantine). */
    public static LeadIngestOutcome silent(LeadIngestStatus status) {
        return new LeadIngestOutcome(status, null, null, null, List.of(), null, null);
    }

    public boolean shouldNotify() {
        return notifyType != null && !recipientUserIds.isEmpty();
    }
}
