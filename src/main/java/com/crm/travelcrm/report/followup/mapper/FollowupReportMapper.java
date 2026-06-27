package com.crm.travelcrm.report.followup.mapper;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.report.followup.dto.FollowupTaskDTO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Hand-written mapper from a {@link Reminder} (+ its optional {@link Lead}) to a
 * {@link FollowupTaskDTO}. Instants are rendered in the server's default zone; the lead's
 * stage / type / travel date are read from the (batch-loaded) lead when present.
 */
@Component
public class FollowupReportMapper {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("MMM dd, yyyy").withZone(ZONE);
    private static final DateTimeFormatter TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("MMM dd");

    public FollowupTaskDTO toDTO(Reminder r, Lead lead) {
        return FollowupTaskDTO.builder()
                .publicId(r.getPublicId())
                .dueDate(r.getDueDate() != null ? DATE_FMT.format(r.getDueDate()) : null)
                .dueTime(r.getDueDate() != null ? TIME_FMT.format(r.getDueDate()) : null)
                .overdueBy(overdueBy(r.getDueDate()))
                .leadName(r.getLeadName())
                .leadPhone(r.getPhone())
                .leadTemp(lead != null && lead.getLeadType() != null
                        ? lead.getLeadType().getDisplayName() : null)
                .assignedTo(r.getAssignToName())
                .assignedUsername(deriveUsername(r.getAssignToName()))
                .assignedUserPublicId(r.getAssignToPublicId())
                .type(r.getType() != null ? r.getType().name().replace('_', ' ') : null)
                .priority(r.getPriority() != null ? r.getPriority().name() : null)
                .title(r.getTitle())
                .desc(r.getDescription())
                .stage(lead != null && lead.getLeadStage() != null
                        ? lead.getLeadStage().getDisplayName() : null)
                .travelDate(lead != null && lead.getTravelDate() != null
                        ? lead.getTravelDate().format(SHORT_FMT) : "N/A")
                .completed(r.getStatus() == ReminderStatus.Completed)
                .build();
    }

    private static String overdueBy(Instant dueDate) {
        if (dueDate == null) {
            return "Upcoming";
        }
        LocalDate due   = dueDate.atZone(ZONE).toLocalDate();
        LocalDate today = LocalDate.now(ZONE);
        long days = ChronoUnit.DAYS.between(due, today);
        if (days > 0) {
            return "Overdue by " + days + " day" + (days > 1 ? "s" : "");
        }
        return "Upcoming";
    }

    private static String deriveUsername(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        return "@" + fullName.trim().toLowerCase().replaceAll("\\s+", "_");
    }
}