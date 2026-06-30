package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.dto.AddLeadLogRequestDto;
import com.crm.travelcrm.lead.dto.LeadLogCardDto;
import com.crm.travelcrm.lead.dto.LeadLogResponseDto;
import com.crm.travelcrm.lead.dto.LeadLogStatsDto;
import com.crm.travelcrm.lead.dto.LeadLogSummaryResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadLog;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.repository.LeadLogRepository;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.reminder.dto.CreateReminderRequestDto;
import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderType;
import com.crm.travelcrm.reminder.service.ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadLogServiceImpl implements LeadLogService {

    private final LeadLogRepository leadLogRepository;
    private final LeadAccessGuard   leadAccessGuard;
    private final ReminderService   reminderService;
    private final ScopeResolver     scopeResolver;
    private final UserRepository    userRepository;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);
    private static final int SUMMARY_PER_PAGE_FOR_STATS = 12;

    // ── Create ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadLogResponseDto addLog(UUID leadPublicId, AddLeadLogRequestDto request) {
        // Resolve the lead under tenant + row-level scope. Adding a log is a write → LEAD_UPDATE.
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE");

        if (request.isCreateReminder() && request.getFollowUpDate() == null) {
            throw new BusinessException(
                    "Follow-up date is required when creating a reminder", HttpStatus.BAD_REQUEST);
        }

        User author = currentUser();

        LeadLog logEntry = LeadLog.builder()
                .lead(lead)
                .comment(request.getComment().trim())
                .stageSnapshot(lead.getLeadStage())     // snapshot the lead's REAL current stage
                .followUpDate(request.getFollowUpDate())
                .addedByUserId(author != null ? author.getId() : null)
                .addedByName(author != null ? author.getName() : "system")
                .build();
        // tenantId is auto-stamped by TenantEntityListener on @PrePersist.
        LeadLog saved = leadLogRepository.save(logEntry);
        log.info("Lead log added | lead: {} | logId: {}", lead.getPublicId(), saved.getPublicId());

        // Optional follow-up reminder — delegated to the reminder module so all its rules apply.
        if (request.isCreateReminder() && request.getFollowUpDate() != null) {
            createFollowUpReminder(lead, request, author);
        }

        return toResponse(saved);
    }

    // ── Read ────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LeadLogResponseDto> getLogsForLead(UUID leadPublicId) {
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_READ");
        return leadLogRepository
                .findByLead_IdAndDeletedAtIsNullOrderByCreatedAtDesc(lead.getId())
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public LeadLogSummaryResponseDto getLogSummary(String search, String stage, UUID userPublicId,
                                                   int page, int perPage) {
        final String q = (search == null || search.isBlank()) ? null : search.trim().toLowerCase();
        final LeadStage stageFilter = parseStage(stage);
        final Long userFilter = resolveUserFilter(userPublicId);

        // Group newest-first so the first log seen per lead is its latest.
        Map<Long, List<LeadLog>> byLead = new LinkedHashMap<>();
        for (LeadLog ll : visibleLogs()) {
            byLead.computeIfAbsent(ll.getLead().getId(), k -> new ArrayList<>()).add(ll);
        }

        List<LeadLogCardDto> cards = new ArrayList<>();
        for (List<LeadLog> leadLogs : byLead.values()) {
            LeadLog latest = leadLogs.get(0);
            Lead lead = latest.getLead();
            if (stageFilter != null && lead.getLeadStage() != stageFilter) continue;
            if (userFilter != null
                    && leadLogs.stream().noneMatch(l -> userFilter.equals(l.getAddedByUserId()))) continue;
            if (q != null) {
                boolean match = (lead.getCustomerName() != null && lead.getCustomerName().toLowerCase().contains(q))
                        || leadLogs.stream().anyMatch(l -> l.getComment() != null && l.getComment().toLowerCase().contains(q));
                if (!match) continue;
            }
            cards.add(toCard(lead, leadLogs.size(), latest));
        }

        int total = cards.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / perPage));
        int from = Math.max(0, (page - 1) * perPage);
        int to = Math.min(from + perPage, total);
        List<LeadLogCardDto> pageItems = from >= total ? List.of() : new ArrayList<>(cards.subList(from, to));

        return LeadLogSummaryResponseDto.builder()
                .leads(pageItems).total(total).page(page).perPage(perPage).totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional
    public void deleteLog(UUID leadPublicId, UUID logPublicId) {
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE");
        LeadLog logEntry = leadLogRepository
                .findByPublicIdAndLead_IdAndDeletedAtIsNull(logPublicId, lead.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Log not found: " + logPublicId));
        logEntry.softDelete(currentUserEmail());
        leadLogRepository.save(logEntry);
        log.info("Lead log deleted | lead: {} | logId: {}", lead.getPublicId(), logPublicId);
    }

    @Override
    @Transactional(readOnly = true)
    public LeadLogStatsDto getLogStats() {
        List<LeadLog> logs = visibleLogs();
        long totalLogs = logs.size();
        long totalLeads = logs.stream().map(l -> l.getLead().getId()).distinct().count();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalLeads / SUMMARY_PER_PAGE_FOR_STATS));
        String today = LocalDate.now().format(DATE_ONLY);
        return LeadLogStatsDto.builder()
                .totalLogs(totalLogs).totalLeads(totalLeads).today(today).totalPages(totalPages)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Tenant logs whose lead falls within the caller's row-level scope, newest first. */
    private List<LeadLog> visibleLogs() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        Set<Long> visibleIds = scopeResolver.visibleUserIds(requireUser(), "LEAD_READ");
        List<LeadLog> all = leadLogRepository.findAllForTenantWithLead(tenantId);
        if (visibleIds == null) return all;             // ALL — no owner restriction
        if (visibleIds.isEmpty()) return List.of();     // NONE — sees nothing
        return all.stream()
                .filter(ll -> ll.getLead().getAssignedUser() != null
                        && visibleIds.contains(ll.getLead().getAssignedUser().getId()))
                .toList();
    }

    private void createFollowUpReminder(Lead lead, AddLeadLogRequestDto request, User author) {
        CreateReminderRequestDto r = new CreateReminderRequestDto();
        r.setTitle("Follow-up: " + lead.getCustomerName());
        r.setDescription(request.getComment().trim());
        r.setType(ReminderType.Follow_up);
        r.setPriority(ReminderPriority.Medium);
        r.setLeadPublicId(lead.getPublicId());
        r.setLeadName(lead.getCustomerName());
        r.setPhone(lead.getPhone());
        if (author != null) r.setAssignToPublicId(author.getPublicId());
        // Due at 09:00 local time on the chosen day.
        r.setDueDate(request.getFollowUpDate()
                .atTime(LocalTime.of(9, 0))
                .atZone(ZoneId.systemDefault())
                .toInstant());
        reminderService.create(r);
        log.info("Follow-up reminder created from lead log | lead: {}", lead.getPublicId());
    }

    private LeadLogCardDto toCard(Lead lead, long count, LeadLog latest) {
        LeadLogCardDto.LatestLog latestDto = LeadLogCardDto.LatestLog.builder()
                .date(latest.getCreatedAt() != null ? latest.getCreatedAt().format(DATE_TIME) : null)
                .comment(latest.getComment())
                .addedBy(latest.getAddedByName())
                .followUpDate(latest.getFollowUpDate() != null ? latest.getFollowUpDate().format(DATE_ONLY) : null)
                .build();
        return LeadLogCardDto.builder()
                .leadId(lead.getPublicId())
                .leadName(lead.getCustomerName())
                .phone(lead.getPhone())
                .stage(lead.getLeadStage() != null ? lead.getLeadStage().getDisplayName() : null)
                .logCount(count)
                .latestLog(latestDto)
                .build();
    }

    private LeadLogResponseDto toResponse(LeadLog l) {
        return LeadLogResponseDto.builder()
                .id(l.getPublicId())
                .comment(l.getComment())
                .stage(l.getStageSnapshot())
                .followUpDate(l.getFollowUpDate())
                .addedBy(l.getAddedByName())
                .createdAt(l.getCreatedAt())
                .build();
    }

    private LeadStage parseStage(String stage) {
        if (stage == null || stage.isBlank() || "All Stages".equalsIgnoreCase(stage)) return null;
        try {
            return LeadStage.fromValue(stage);
        } catch (IllegalArgumentException e) {
            return null;   // unknown filter value → no stage restriction
        }
    }

    private Long resolveUserFilter(UUID userPublicId) {
        if (userPublicId == null) return null;
        return userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, TenantContext.getTenantId())
                .map(User::getId).orElse(null);
    }

    /** Logged-in principal's username/email for the soft-delete audit stamp. */
    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** The authenticated tenant user, or null (e.g. SuperAdmin) — used for log authorship. */
    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
    }

    /** Like {@link #currentUser()} but mandatory — for scope resolution on read endpoints. */
    private User requireUser() {
        User u = currentUser();
        if (u == null) throw new IllegalStateException("No tenant user in security context");
        return u;
    }
}