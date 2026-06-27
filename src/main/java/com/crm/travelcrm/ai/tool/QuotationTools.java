package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.service.QuotationService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only quotation tools. Uses the lead-scoped paths in {@link QuotationService}
 * ({@code getLatestByLead}/{@code getByLead}), which resolve visibility through the lead's access
 * guard — so a user only sees quotations for leads they're allowed to see.
 */
@Component
@RequiredArgsConstructor
public class QuotationTools {

    private final QuotationService quotationService;
    private final AiAuditService audit;

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
                    QuotationSummaryDto q = quotationService.getLatestByLead(ToolFmt.uuid(leadPublicId));
                    return q == null ? null : toStatus(q);
                });
    }

    @Tool(description = "List all quotations for a lead by the lead's publicId (every version), newest first.")
    public List<QuotationStatus> listQuotationsForLead(
            @ToolParam(description = "The lead's publicId (UUID)") String leadPublicId) {
        return audit.recordToolCall("listQuotationsForLead", Map.of("leadPublicId", ToolFmt.str(leadPublicId)),
                () -> quotationService.getByLead(ToolFmt.uuid(leadPublicId)).stream()
                        .map(QuotationTools::toStatus).toList());
    }

    private static QuotationStatus toStatus(QuotationSummaryDto q) {
        return new QuotationStatus(
                ToolFmt.str(q.getPublicId()), ToolFmt.str(q.getLeadId()), q.getTitle(), q.getVersion(),
                ToolFmt.str(q.getQuotationStage()), ToolFmt.str(q.getLeadStage()),
                q.getCustomerName(), q.getDestination(), ToolFmt.str(q.getTravelDate()),
                ToolFmt.str(q.getGrandTotal()), ToolFmt.str(q.getCreatedAt()));
    }
}