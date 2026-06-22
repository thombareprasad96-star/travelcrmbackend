package com.crm.travelcrm.common.event;

/**
 * Published when a lead is soft-deleted. Lives in {@code common} (shared infra both
 * the lead and quotation modules already depend on) so neither feature module has to
 * import the other — the event is the only contract between them.
 *
 * @param leadId   internal {@code Lead.id} (matches the logical {@code Quotation.leadId} FK)
 * @param tenantId owning tenant, so listeners stay tenant-scoped
 */
public record LeadSoftDeletedEvent(Long leadId, Long tenantId) {
}
