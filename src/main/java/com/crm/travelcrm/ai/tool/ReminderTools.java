package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.ai.service.AiAuditService;
import com.crm.travelcrm.reminder.dto.ReminderResponseDto;
import com.crm.travelcrm.reminder.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only reminder tools. Delegates to {@link ReminderService} (tenant-scoped). The internal Long
 * id is never surfaced — only publicId/UUID references and safe display fields.
 */
@Component
@RequiredArgsConstructor
public class ReminderTools {

    private final ReminderService reminderService;
    private final AiAuditService audit;

    public record ReminderSummary(String title, String description, String type, String priority,
                                  String status, String leadName, String phone, String dueDate,
                                  String leadPublicId, String assignedTo, String assignToPublicId,
                                  String createdAt) {}

    @Tool(description = "List reminders, optionally filtered by status. Returns title, due date, "
            + "priority, status and the related lead. Internal ids are not exposed.")
    public List<ReminderSummary> getMyReminders(
            @ToolParam(required = false, description =
                    "Optional status filter, e.g. PENDING, COMPLETED, DISMISSED") String status) {
        return audit.recordToolCall("getMyReminders", Map.of("status", ToolFmt.str(status)),
                () -> map(reminderService.getAll(status, null, null)));
    }

    @Tool(description = "List reminders that are overdue (past their due date and not completed).")
    public List<ReminderSummary> getOverdueReminders() {
        return audit.recordToolCall("getOverdueReminders", Map.of(),
                () -> map(reminderService.getOverdue()));
    }

    @Tool(description = "List reminders that are due today.")
    public List<ReminderSummary> getDueTodayReminders() {
        return audit.recordToolCall("getDueTodayReminders", Map.of(),
                () -> map(reminderService.getDueToday()));
    }

    private static List<ReminderSummary> map(List<ReminderResponseDto> list) {
        return list.stream().map(ReminderTools::toSummary).toList();
    }

    private static ReminderSummary toSummary(ReminderResponseDto r) {
        return new ReminderSummary(
                r.getTitle(), r.getDescription(), ToolFmt.str(r.getType()), ToolFmt.str(r.getPriority()),
                ToolFmt.str(r.getStatus()), r.getLeadName(), r.getPhone(), ToolFmt.str(r.getDueDate()),
                ToolFmt.str(r.getLeadPublicId()), r.getAssignToName(), ToolFmt.str(r.getAssignToPublicId()),
                ToolFmt.str(r.getCreatedAt()));
    }
}