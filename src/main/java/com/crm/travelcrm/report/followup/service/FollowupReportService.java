package com.crm.travelcrm.report.followup.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.report.followup.dto.BulkCompleteResponseDTO;
import com.crm.travelcrm.report.followup.dto.FollowupSummaryDTO;
import com.crm.travelcrm.report.followup.dto.FollowupTaskDTO;
import com.crm.travelcrm.report.followup.dto.FollowupTasksResponseDTO;
import com.crm.travelcrm.report.followup.mapper.FollowupReportMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read/command side of the Follow-up Report. Maps {@code Reminder}s (joined to their {@code Lead}
 * for stage / type / travel date) onto the FE's task rows; "complete" sets
 * {@link ReminderStatus#Completed} (there is no boolean completed flag). All access is tenant-scoped
 * via {@link TenantContext}; the {@code assignedTo} filter is a user <b>publicId</b> matched against
 * the reminder's denormalised {@code assignToPublicId}. Bare DTOs per the reports contract.
 */
@Service
@RequiredArgsConstructor
public class FollowupReportService {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ReminderRepository reminderRepository;
    private final LeadRepository leadRepository;
    private final FollowupReportMapper mapper;

    @Transactional(readOnly = true)
    public FollowupTasksResponseDTO getTasks(String viewType, String daysAhead, String assignedTo,
                                             String priority, String reminderType, String search,
                                             int perPage, int page) {
        Long tenantId = requireTenant();
        int safePage    = Math.max(page, 1);
        int safePerPage = Math.min(Math.max(perPage, 1), 500);
        Pageable pageable = PageRequest.of(safePage - 1, safePerPage, Sort.by(Sort.Direction.ASC, "dueDate"));

        Specification<Reminder> spec = buildSpec(
                tenantId, viewType, daysAhead, parseUuid(assignedTo),
                parsePriority(priority), parseType(reminderType), search);

        Page<Reminder> result = reminderRepository.findAll(spec, pageable);
        List<FollowupTaskDTO> tasks = enrich(tenantId, result.getContent());

        return FollowupTasksResponseDTO.builder()
                .tasks(tasks)
                .total(result.getTotalElements())
                .page(safePage)
                .perPage(safePerPage)
                .totalPages(result.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public FollowupSummaryDTO getSummary(String assignedTo, String priority, String reminderType) {
        Long tenantId = requireTenant();
        UUID assignee = parseUuid(assignedTo);
        ReminderPriority pri = parsePriority(priority);
        ReminderType type = parseType(reminderType);

        return FollowupSummaryDTO.builder()
                .totalFollowups(reminderRepository.count(buildSpec(tenantId, "Open",      null, assignee, pri, type, null)))
                .overdue(reminderRepository.count(buildSpec(tenantId, "Overdue",          null, assignee, pri, type, null)))
                .dueToday(reminderRepository.count(buildSpec(tenantId, "Due Today",       null, assignee, pri, type, null)))
                .urgent(reminderRepository.count(buildSpec(tenantId, "Urgent",            null, assignee, pri, type, null)))
                .upcoming(reminderRepository.count(buildSpec(tenantId, "Upcoming",        null, assignee, pri, type, null)))
                .highPriority(reminderRepository.count(buildSpec(tenantId, "Open",        null, assignee, ReminderPriority.High, type, null)))
                .build();
    }

    @Transactional
    public FollowupTaskDTO markComplete(UUID publicId) {
        Long tenantId = requireTenant();
        Reminder reminder = reminderRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Reminder not found"));
        reminder.setStatus(ReminderStatus.Completed);
        Reminder saved = reminderRepository.save(reminder);
        Lead lead = saved.getLeadRefId() != null
                ? leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(saved.getLeadRefId(), tenantId).orElse(null)
                : null;
        return mapper.toDTO(saved, lead);
    }

    @Transactional
    public BulkCompleteResponseDTO bulkComplete(List<UUID> ids) {
        Long tenantId = requireTenant();
        int completed = 0, failed = 0;
        if (ids != null) {
            for (UUID id : ids) {
                var reminder = reminderRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(id, tenantId);
                if (reminder.isPresent()) {
                    reminder.get().setStatus(ReminderStatus.Completed);
                    reminderRepository.save(reminder.get());
                    completed++;
                } else {
                    failed++;
                }
            }
        }
        return BulkCompleteResponseDTO.builder()
                .completed(completed)
                .failed(failed)
                .message(completed + " task" + (completed == 1 ? "" : "s") + " marked as completed.")
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(String viewType, String daysAhead, String assignedTo,
                            String priority, String reminderType, String search) {
        Long tenantId = requireTenant();
        Specification<Reminder> spec = buildSpec(
                tenantId, viewType, daysAhead, parseUuid(assignedTo),
                parsePriority(priority), parseType(reminderType), search);
        List<Reminder> reminders = reminderRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "dueDate"));
        List<FollowupTaskDTO> tasks = enrich(tenantId, reminders);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Due Date", "Due Time", "Overdue By", "Lead Name", "Phone",
                    "Temperature", "Assigned To", "Type", "Priority", "Title", "Description",
                    "Lead Stage", "Travel Date", "Completed");
            for (FollowupTaskDTO t : tasks) {
                printer.printRecord(t.getDueDate(), t.getDueTime(), t.getOverdueBy(), t.getLeadName(),
                        t.getLeadPhone(), t.getLeadTemp(), t.getAssignedTo(), t.getType(), t.getPriority(),
                        t.getTitle(), t.getDesc(), t.getStage(), t.getTravelDate(), t.isCompleted());
            }
            printer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate follow-up CSV export", e);
        }
    }

    // ── enrichment ───────────────────────────────────────────────────────────

    /** Batch-load the referenced leads once, then map each reminder with its lead. */
    private List<FollowupTaskDTO> enrich(Long tenantId, List<Reminder> reminders) {
        List<Long> leadIds = reminders.stream()
                .map(Reminder::getLeadRefId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Lead> leadsById = leadIds.isEmpty()
                ? Map.of()
                : leadRepository.findByTenantIdAndIdInAndDeletedAtIsNull(tenantId, leadIds).stream()
                        .collect(Collectors.toMap(Lead::getId, Function.identity()));
        return reminders.stream()
                .map(r -> mapper.toDTO(r, r.getLeadRefId() != null ? leadsById.get(r.getLeadRefId()) : null))
                .toList();
    }

    // ── specification ────────────────────────────────────────────────────────

    private Specification<Reminder> buildSpec(Long tenantId, String viewType, String daysAhead,
                                              UUID assignedTo, ReminderPriority priority,
                                              ReminderType type, String search) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            ps.add(cb.isNull(root.get("deletedAt")));
            if (assignedTo != null) ps.add(cb.equal(root.get("assignToPublicId"), assignedTo));
            if (priority != null)   ps.add(cb.equal(root.get("priority"), priority));
            if (type != null)       ps.add(cb.equal(root.get("type"), type));
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("leadName"), cb.literal(""))), like),
                        cb.like(cb.lower(cb.coalesce(root.get("assignToName"), cb.literal(""))), like)));
            }
            addViewPredicate(ps, cb, root, viewType, daysAhead);
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private void addViewPredicate(List<Predicate> ps, jakarta.persistence.criteria.CriteriaBuilder cb,
                                  jakarta.persistence.criteria.Root<Reminder> root,
                                  String viewType, String daysAhead) {
        Instant now = Instant.now();
        String view = (viewType == null || viewType.isBlank()) ? "All" : viewType;
        switch (view) {
            case "Overdue" -> {
                ps.add(notCompleted(cb, root));
                ps.add(cb.lessThan(root.<Instant>get("dueDate"), now));
            }
            case "Due Today" -> {
                ps.add(notCompleted(cb, root));
                ps.add(cb.between(root.<Instant>get("dueDate"), startOfToday(), endOfToday()));
            }
            case "Completed" -> ps.add(cb.equal(root.get("status"), ReminderStatus.Completed));
            case "Upcoming" -> {
                ps.add(notCompleted(cb, root));
                ps.add(cb.greaterThanOrEqualTo(root.<Instant>get("dueDate"), now));
                int days = parseDays(daysAhead);
                if (days > 0) {
                    ps.add(cb.lessThanOrEqualTo(root.<Instant>get("dueDate"), now.plus(Duration.ofDays(days))));
                }
            }
            case "Urgent" -> {
                ps.add(notCompleted(cb, root));
                ps.add(cb.between(root.<Instant>get("dueDate"), now, now.plus(Duration.ofDays(3))));
            }
            case "Open" -> ps.add(notCompleted(cb, root));
            default -> { /* "All" — no status/date constraint */ }
        }
    }

    private static Predicate notCompleted(jakarta.persistence.criteria.CriteriaBuilder cb,
                                          jakarta.persistence.criteria.Root<Reminder> root) {
        return cb.not(root.get("status").in(ReminderStatus.Completed, ReminderStatus.Dismissed));
    }

    // ── parsing helpers ──────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read follow-up reports.");
        }
        return tenantId;
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    private static Instant endOfToday() {
        return LocalDate.now(ZONE).atTime(LocalTime.MAX).atZone(ZONE).toInstant();
    }

    private static int parseDays(String daysAhead) {
        if (daysAhead == null || daysAhead.isBlank() || daysAhead.equalsIgnoreCase("All")) {
            return 0;
        }
        try {
            return Integer.parseInt(daysAhead.trim().split("\\s+")[0]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ReminderPriority parsePriority(String s) {
        if (s == null || s.isBlank()) return null;
        for (ReminderPriority p : ReminderPriority.values()) {
            if (p.name().equalsIgnoreCase(s.trim())) return p;
        }
        return null;
    }

    private static ReminderType parseType(String s) {
        if (s == null || s.isBlank()) return null;
        String normalized = s.trim().replaceAll("\\s+", "_");
        for (ReminderType t : ReminderType.values()) {
            if (t.name().equalsIgnoreCase(normalized)) return t;
        }
        return null;
    }
}