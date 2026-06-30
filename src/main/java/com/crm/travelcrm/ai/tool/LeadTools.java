package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.service.LeadService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only lead tools. Pure delegation to {@link LeadService}, which already enforces tenant
 * isolation AND the caller's row-level scope (own/team/all). No new queries; publicId only.
 */
@Component
@RequiredArgsConstructor
public class LeadTools {

    private final LeadService leadService;
    private final AiAuditService audit;

    public record LeadSummary(String publicId, String customerName, String phone, String email,
                              String leadStage, String leadType, String assignedTo,
                              String travelDate, String budget,
                              String departCity, String departCountry) {}

    public record LeadDetail(String publicId, String customerName, String phone, String email,
                             String leadStage, String leadType, String assignedTo,
                             String travelDate, String budget,
                             String departCity, String departCountry,
                             List<String> services, String notes, String createdAt,
                             String convertedBookingPublicId) {}

    @Tool(description = "List leads visible to the current user (paginated, newest first). "
            + "Returns lead summaries with publicId, customer, stage and assignee.")
    public List<LeadSummary> findLeads(
            @ToolParam(required = false, description = "Zero-based page number (default 0)") Integer page,
            @ToolParam(required = false, description = "Page size, max 50 (default 20)") Integer size) {
        return audit.recordToolCall("findLeads", Map.of(
                        "page", ToolFmt.pageOrDefault(page), "size", ToolFmt.sizeOrDefault(size)),
                () -> leadService.getAllLeads(ToolFmt.pageOrDefault(page), ToolFmt.sizeOrDefault(size),
                                "createdAt", "desc")
                        .getContent().stream().map(LeadTools::toSummary).toList());
    }

    @Tool(description = "Find a single lead by a free-text keyword (name, phone or email). "
            + "Returns the best matching lead summary, or null if none.")
    public LeadSummary searchLeadByKeyword(
            @ToolParam(description = "Search keyword: customer name, phone or email") String keyword) {
        return audit.recordToolCall("searchLeadByKeyword", Map.of("keyword", ToolFmt.str(keyword)),
                () -> {
                    LeadResponseDto d = leadService.searchLead(keyword);
                    return d == null ? null : toSummary(d);
                });
    }

    @Tool(description = "Get full details for one lead by its publicId, including services, notes "
            + "and whether it has been converted to a booking.")
    public LeadDetail getLeadDetails(
            @ToolParam(description = "The lead's publicId (UUID)") String leadPublicId) {
        return audit.recordToolCall("getLeadDetails", Map.of("leadPublicId", ToolFmt.str(leadPublicId)),
                () -> toDetail(leadService.getLeadById(ToolFmt.uuid(leadPublicId))));
    }

    private static LeadSummary toSummary(LeadResponseDto d) {
        return new LeadSummary(
                ToolFmt.str(d.getId()), d.getCustomerName(), d.getPhone(), d.getEmail(),
                ToolFmt.str(d.getLeadStage()), ToolFmt.str(d.getLeadType()),
                d.getAssignedUser() != null ? d.getAssignedUser().getFullName() : null,
                ToolFmt.str(d.getTravelDate()), ToolFmt.str(d.getBudget()),
                d.getDepartCity(), d.getDepartCountry());
    }

    private static LeadDetail toDetail(LeadResponseDto d) {
        return new LeadDetail(
                ToolFmt.str(d.getId()), d.getCustomerName(), d.getPhone(), d.getEmail(),
                ToolFmt.str(d.getLeadStage()), ToolFmt.str(d.getLeadType()),
                d.getAssignedUser() != null ? d.getAssignedUser().getFullName() : null,
                ToolFmt.str(d.getTravelDate()), ToolFmt.str(d.getBudget()),
                d.getDepartCity(), d.getDepartCountry(),
                d.getServices(), d.getNotes(), ToolFmt.str(d.getCreatedAt()),
                ToolFmt.str(d.getConvertedBookingPublicId()));
    }
}