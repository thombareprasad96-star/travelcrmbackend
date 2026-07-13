package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.common.exception.BadRequestException;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.service.QuotationService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only quotation tools. Delegates to {@link QuotationService}; these read paths are
 * tenant-scoped (they do NOT apply per-lead row-level visibility), so each tool enforces the same
 * {@code QUOTATION_READ} authority the REST controller requires before returning any data.
 */
@Component
@RequiredArgsConstructor
public class QuotationTools {

    private final QuotationService quotationService;
    private final AiAuditService audit;
    private final AiToolAuthorizer authorizer;

    public record QuotationStatus(String publicId, String leadPublicId, String title, String version,
                                  String quotationStage, String leadStage, String customerName,
                                  String destination, String travelDate, String grandTotal,
                                  String createdAt) {}

    @Tool(description = "Get the latest quotation for a lead by the lead's publicId, including its "
            + "stage and grand total. Returns null if the lead has no quotation yet.")
    public QuotationStatus getQuotationStatus(
            @ToolParam(description = "The lead's publicId (UUID)") String leadPublicId) {
        return audit.recordToolCall("getQuotationStatus", Map.of("leadPublicId", ToolFmt.str(leadPublicId)),
                () -> {
                    authorizer.require("QUOTATION_READ");
                    QuotationSummaryDto q = quotationService.getLatestByLead(ToolFmt.uuid(leadPublicId));
                    return q == null ? null : toStatus(q);
                });
    }

    @Tool(description = "List all quotations for a lead by the lead's publicId (every version), newest first.")
    public List<QuotationStatus> listQuotationsForLead(
            @ToolParam(description = "The lead's publicId (UUID)") String leadPublicId) {
        return audit.recordToolCall("listQuotationsForLead", Map.of("leadPublicId", ToolFmt.str(leadPublicId)),
                () -> {
                    authorizer.require("QUOTATION_READ");
                    return quotationService.getByLead(ToolFmt.uuid(leadPublicId)).stream()
                            .map(QuotationTools::toStatus).toList();
                });
    }

    @Tool(description = "List quotations across all visible leads (paginated, newest first), optionally "
            + "filtered by stage. Use this for questions like 'pending/draft/sent quotations' or "
            + "'quotations this week' (read createdAt to judge recency). Stage is one of Draft, Sent, "
            + "Approved, Rejected; omit it for all stages.")
    public List<QuotationStatus> findQuotations(
            @ToolParam(required = false, description =
                    "Stage filter: Draft, Sent, Approved or Rejected (omit for all stages)") String stage,
            @ToolParam(required = false, description = "Zero-based page number (default 0)") Integer page,
            @ToolParam(required = false, description = "Page size, max 50 (default 20)") Integer size) {
        return audit.recordToolCall("findQuotations", Map.of(
                        "stage", stage == null || stage.isBlank() ? "(all)" : stage,
                        "page", ToolFmt.pageOrDefault(page), "size", ToolFmt.sizeOrDefault(size)),
                () -> {
                    authorizer.require("QUOTATION_READ");
                    return quotationService.search(null, parseStage(stage), null,
                                    ToolFmt.pageOrDefault(page), ToolFmt.sizeOrDefault(size), "createdAt", "desc")
                            .getContent().stream().map(QuotationTools::toStatus).toList();
                });
    }

    /** Null/blank stage means "all stages"; an unknown value gets a readable error, not a 500. */
    private static QuotationStage parseStage(String stage) {
        if (stage == null || stage.isBlank()) return null;
        try {
            return QuotationStage.fromValue(stage);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(
                    "Invalid quotation stage: '" + stage + "'. Allowed: Draft, Sent, Approved, Rejected.");
        }
    }

    private static QuotationStatus toStatus(QuotationSummaryDto q) {
        return new QuotationStatus(
                ToolFmt.str(q.getPublicId()), ToolFmt.str(q.getLeadId()), q.getTitle(), q.getVersion(),
                ToolFmt.str(q.getQuotationStage()), ToolFmt.str(q.getLeadStage()),
                q.getCustomerName(), q.getDestination(), ToolFmt.str(q.getTravelDate()),
                ToolFmt.str(q.getGrandTotal()), ToolFmt.str(q.getCreatedAt()));
    }
}