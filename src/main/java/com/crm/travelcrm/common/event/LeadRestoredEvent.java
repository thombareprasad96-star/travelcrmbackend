package com.crm.travelcrm.common.event;

/**
 * Published when a soft-deleted lead is restored from Trash. Mirrors
 * {@link LeadSoftDeletedEvent}: lives in {@code common} so the quotation module can
 * cascade-restore the lead's quotations without either feature module importing the other.
 *
 * @param leadId   internal {@code Lead.id} (matches the logical {@code Quotation.leadId} FK)
 * @param tenantId owning tenant, so listeners stay tenant-scoped
 */
public record LeadRestoredEvent(Long leadId, Long tenantId) {
}