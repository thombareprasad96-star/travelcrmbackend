package com.crm.travelcrm.report.activity.mapper;

import com.crm.travelcrm.activity.entity.ActivityLog;
import com.crm.travelcrm.report.activity.dto.ActivityLogDTO;
import com.crm.travelcrm.report.activity.dto.ActivityLogDetailDTO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Hand-written mapper (no MapStruct) from the {@link ActivityLog} entity to the report DTOs.
 * Formats the audit timestamp into the FE's display strings and derives a {@code @username} from
 * the email snapshot.
 */
@Component
public class ActivityReportMapper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public ActivityLogDTO toDTO(ActivityLog log) {
        return ActivityLogDTO.builder()
                .publicId(log.getPublicId())
                .date(log.getCreatedAt() != null ? log.getCreatedAt().format(DATE_FMT) : null)
                .time(log.getCreatedAt() != null ? log.getCreatedAt().format(TIME_FMT) : null)
                .user(log.getUserName())
                .username(deriveUsername(log.getUserEmail(), log.getUserName()))
                .type(log.getUserType())
                .action(log.getAction() != null ? log.getAction().name() : null)
                .description(log.getDescription())
                .ip(log.getIpAddress())
                .build();
    }

    public ActivityLogDetailDTO toDetailDTO(ActivityLog log) {
        return ActivityLogDetailDTO.builder()
                .publicId(log.getPublicId())
                .date(log.getCreatedAt() != null ? log.getCreatedAt().format(DATE_FMT) : null)
                .time(log.getCreatedAt() != null ? log.getCreatedAt().format(TIME_FMT) : null)
                .user(log.getUserName())
                .username(deriveUsername(log.getUserEmail(), log.getUserName()))
                .type(log.getUserType())
                .action(log.getAction() != null ? log.getAction().name() : null)
                .description(log.getDescription())
                .ip(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .build();
    }

    /** "alice@x.com" → "@alice"; falls back to the full name with spaces collapsed to underscores. */
    private static String deriveUsername(String email, String fullName) {
        if (email != null && email.contains("@")) {
            return "@" + email.substring(0, email.indexOf('@'));
        }
        if (fullName != null && !fullName.isBlank()) {
            return "@" + fullName.trim().replaceAll("\\s+", "_");
        }
        return null;
    }
}