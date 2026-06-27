package com.crm.travelcrm.report.activity.service;

import com.crm.travelcrm.activity.entity.ActivityAction;
import com.crm.travelcrm.activity.entity.ActivityLog;
import com.crm.travelcrm.activity.repository.ActivityLogRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.report.activity.dto.ActivityLogDTO;
import com.crm.travelcrm.report.activity.dto.ActivityLogDetailDTO;
import com.crm.travelcrm.report.activity.dto.ActivityLogsResponseDTO;
import com.crm.travelcrm.report.activity.dto.ActivitySummaryDTO;
import com.crm.travelcrm.report.activity.mapper.ActivityReportMapper;
import com.crm.travelcrm.report.support.ReportDateRange;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the Activity Reports. All queries are tenant-scoped via {@link TenantContext}; the
 * {@code userId} filter the FE sends is a user <b>publicId</b> (UUID), resolved here to the internal
 * id before querying. Returns bare DTOs (no {@code ApiResponse}) per the reports contract.
 */
@Service
@RequiredArgsConstructor
public class ActivityReportService {

    private final ActivityLogRepository activityLogRepository;
    private final UserRepository userRepository;
    private final ActivityReportMapper mapper;

    @Transactional(readOnly = true)
    public ActivityLogsResponseDTO getLogs(String startDate, String endDate, String action,
                                           String userType, String userPublicId,
                                           int perPage, int page) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        ActivityAction actionEnum = parseAction(action);
        Long userId = resolveUserId(userPublicId, tenantId);

        int safePage    = Math.max(page, 1);
        int safePerPage = Math.min(Math.max(perPage, 1), 200);
        Pageable pageable = PageRequest.of(safePage - 1, safePerPage);

        Page<ActivityLog> result = activityLogRepository.findWithFilters(
                tenantId, range[0], range[1], actionEnum, blankToNull(userType), userId, pageable);

        List<ActivityLogDTO> logs = result.getContent().stream().map(mapper::toDTO).toList();

        return ActivityLogsResponseDTO.builder()
                .logs(logs)
                .total(result.getTotalElements())
                .page(safePage)
                .perPage(safePerPage)
                .totalPages(result.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public ActivitySummaryDTO getSummary(String startDate, String endDate) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        return ActivitySummaryDTO.builder()
                .totalActivities(activityLogRepository
                        .countByTenantIdAndCreatedAtBetweenAndDeletedAtIsNull(tenantId, range[0], range[1]))
                .totalLogins(activityLogRepository
                        .countByTenantIdAndActionAndCreatedAtBetweenAndDeletedAtIsNull(
                                tenantId, ActivityAction.Login, range[0], range[1]))
                .adminActions(activityLogRepository
                        .countByTenantIdAndUserTypeAndCreatedAtBetweenAndDeletedAtIsNull(
                                tenantId, "Admin", range[0], range[1]))
                .uniqueUsers(activityLogRepository.countDistinctUsers(tenantId, range[0], range[1]))
                .build();
    }

    @Transactional(readOnly = true)
    public ActivityLogDetailDTO getDetail(UUID publicId) {
        Long tenantId = requireTenant();
        ActivityLog log = activityLogRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity log not found"));
        return mapper.toDetailDTO(log);
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String startDate, String endDate, String action,
                            String userType, String userPublicId) {
        Long tenantId = requireTenant();
        LocalDateTime[] range = ReportDateRange.resolve(startDate, endDate);
        ActivityAction actionEnum = parseAction(action);
        Long userId = resolveUserId(userPublicId, tenantId);

        List<ActivityLog> logs = activityLogRepository.findAllWithFilters(
                tenantId, range[0], range[1], actionEnum, blankToNull(userType), userId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Date", "Time", "User", "Username", "Type", "Action", "Description", "IP Address");
            for (ActivityLog log : logs) {
                ActivityLogDTO dto = mapper.toDTO(log);
                printer.printRecord(dto.getDate(), dto.getTime(), dto.getUser(), dto.getUsername(),
                        dto.getType(), dto.getAction(), dto.getDescription(), dto.getIp());
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate activity CSV export", e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read activity reports.");
        }
        return tenantId;
    }

    /** FE sends a user publicId (UUID). Resolve to the internal id within the tenant; ignore if absent/invalid. */
    private Long resolveUserId(String userPublicId, Long tenantId) {
        if (userPublicId == null || userPublicId.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(userPublicId.trim());
            return userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(uuid, tenantId)
                    .map(u -> u.getId())
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null; // not a UUID — treat as "no user filter"
        }
    }

    private static ActivityAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return ActivityAction.valueOf(action.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}