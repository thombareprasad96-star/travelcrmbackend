package com.crm.travelcrm.subagent.service;

/**
 * White-label branding a sub-agent's documents (quotation/booking PDFs) may carry in place of the
 * parent tenant's {@code Company} branding. Any field may be blank/null — the PDF services apply
 * only the non-blank fields, so a sub-agent who sets just a logo keeps the parent's name, etc.
 */
public record SubAgentBranding(
        String brandName,
        String logoUrl,
        String contactPhone,
        String contactEmail,
        String brandColor) {
}
