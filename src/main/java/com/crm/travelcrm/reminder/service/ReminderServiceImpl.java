package com.crm.travelcrm.reminder.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.reminder.dto.CreateReminderRequestDto;
import com.crm.travelcrm.reminder.dto.ReminderResponseDto;
import com.crm.travelcrm.reminder.dto.ReminderStatsDto;
import com.crm.travelcrm.reminder.dto.UpdateReminderRequestDto;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import com.crm.travelcrm.reminder.mapper.ReminderMapper;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.reminder.specification.ReminderSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReminderServiceImpl implements ReminderService {

    private final ReminderRepository reminderRepository;
    private final ReminderMapper reminderMapper;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;

    private static final DateTimeFormatter CSV_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    /** Both states count as "past due": Active not-yet-flipped and already-flipped OVERDUE. */
    private static final List<ReminderStatus> OVERDUE_STATUSES =
            List.of(ReminderStatus.Active, ReminderStatus.OVERDUE);

    // ── Commands ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ReminderResponseDto create(CreateReminderRequestDto request) {
        Long tenantId = currentTenantId();

        Reminder reminder = reminderMapper.toEntity(request);
        // Defaults (frontend usually sends these, but stay safe for partial payloads).
        if (reminder.getType() == null)     reminder.setType(ReminderType.Custom);
        if (reminder.getPriority() == null) reminder.setPriority(ReminderPriority.Medium);
        if (reminder.getStatus() == null)   reminder.setStatus(ReminderStatus.Active);

        reminder.setTenantId(tenantId);
        reminder.setOwnerUserId(currentUserId());
        reminder.setNotified(false);

        // Resolve + validate the lead/assignee references (publicId → internal Long FK).
        applyReferences(reminder, request.getLeadPublicId(), request.getAssignToPublicId(), tenantId);

        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder created | id: {} | tenantId: {} | due: {}",
                saved.getId(), tenantId, saved.getDueDate());
        return reminderMapper.toDto(saved);
    }

    @Override
    @Transactional
    public ReminderResponseDto update(Long id, UpdateReminderRequestDto request) {
        Reminder reminder = findOrThrow(id);
        Instant previousDue = reminder.getDueDate();

        reminderMapper.updateEntity(request, reminder);

        // Re-resolve references only when the caller supplies a new publicId (partial update).
        applyReferences(reminder, request.getLeadPublicId(), request.getAssignToPublicId(),
                reminder.getTenantId());

        // If the reminder was re-scheduled into the future, allow it to fire again.
        if (request.getDueDate() != null
                && !request.getDueDate().equals(previousDue)
                && reminder.getStatus() == ReminderStatus.Active) {
            reminder.setNotified(false);
        }

        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder updated | id: {} | tenantId: {}", id, saved.getTenantId());
        return reminderMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Reminder reminder = findOrThrow(id);
        reminder.softDelete(currentUsername());
        reminderRepository.save(reminder);
        log.info("Reminder soft-deleted | id: {} | tenantId: {}", id, reminder.getTenantId());
    }

    @Override
    @Transactional
    public ReminderResponseDto markComplete(Long id) {
        return changeStatus(id, ReminderStatus.Completed);
    }

    @Override
    @Transactional
    public ReminderResponseDto dismiss(Long id) {
        return changeStatus(id, ReminderStatus.Dismissed);
    }

    @Override
    @Transactional
    public ReminderResponseDto snooze(Long id, Instant snoozedUntil) {
        Reminder reminder = findOrThrow(id);
        reminder.setStatus(ReminderStatus.Snoozed);
        reminder.setSnoozedUntil(snoozedUntil);
        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder snoozed | id: {} | until: {}", id, snoozedUntil);
        return reminderMapper.toDto(saved);
    }

    @Override
    @Transactional
    public ReminderResponseDto addLog(Long id, String logText) {
        Reminder reminder = findOrThrow(id);
        String stamped = CSV_TS.format(Instant.now()) + " — " + logText.trim();
        reminder.getLogs().add(stamped);
        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder log added | id: {}", id);
        return reminderMapper.toDto(saved);
    }

    @Override
    @Transactional
    public int completeAllOverdue() {
        Long tenantId = currentTenantId();
        List<Reminder> overdue = reminderRepository
                .findByTenantIdAndStatusInAndDueDateLessThanAndDeletedAtIsNullOrderByDueDateAsc(
                        tenantId, OVERDUE_STATUSES, Instant.now());
        overdue.forEach(r -> r.setStatus(ReminderStatus.Completed));
        reminderRepository.saveAll(overdue);
        log.info("Completed {} overdue reminder(s) | tenantId: {}", overdue.size(), tenantId);
        return overdue.size();
    }

    // ── Queries ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ReminderResponseDto> getAll(String status, String priority, String type) {
        Long tenantId = currentTenantId();
        var spec = ReminderSpecification.build(
                tenantId, parseStatus(status), parsePriority(priority), parseType(type));
        return reminderRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "dueDate"))
                .stream().map(reminderMapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReminderResponseDto getById(Long id) {
        return reminderMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderResponseDto> getOverdue() {
        Long tenantId = currentTenantId();
        return reminderRepository
                .findByTenantIdAndStatusInAndDueDateLessThanAndDeletedAtIsNullOrderByDueDateAsc(
                        tenantId, OVERDUE_STATUSES, Instant.now())
                .stream().map(reminderMapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderResponseDto> getDueToday() {
        Long tenantId = currentTenantId();
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        return reminderRepository
                .findByTenantIdAndStatusAndDueDateBetweenAndDeletedAtIsNullOrderByDueDateAsc(
                        tenantId, ReminderStatus.Active, startOfDay, endOfDay)
                .stream().map(reminderMapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderResponseDto> getByLeadName(String leadName) {
        Long tenantId = currentTenantId();
        return reminderRepository
                .findByTenantIdAndLeadNameIgnoreCaseAndDeletedAtIsNullOrderByDueDateAsc(tenantId, leadName)
                .stream().map(reminderMapper::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReminderStatsDto getStats() {
        Long tenantId = currentTenantId();
        Instant now = Instant.now();
        return ReminderStatsDto.builder()
                .total(reminderRepository.countByTenantIdAndDeletedAtIsNull(tenantId))
                .active(reminderRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, ReminderStatus.Active))
                .overdue(reminderRepository.countByTenantIdAndStatusInAndDueDateLessThanAndDeletedAtIsNull(
                        tenantId, OVERDUE_STATUSES, now))
                .completed(reminderRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, ReminderStatus.Completed))
                .snoozed(reminderRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, ReminderStatus.Snoozed))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        Long tenantId = currentTenantId();
        var spec = ReminderSpecification.build(tenantId, null, null, null);
        List<Reminder> all = reminderRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "dueDate"));

        StringBuilder sb = new StringBuilder();
        sb.append("id,title,type,priority,status,leadId,leadName,phone,assignTo,dueDate,snoozedUntil,notes\n");
        for (Reminder r : all) {
            sb.append(r.getId()).append(',')
                    .append(csv(r.getTitle())).append(',')
                    .append(r.getType()).append(',')
                    .append(r.getPriority()).append(',')
                    .append(r.getStatus()).append(',')
                    .append(csv(r.getLeadId())).append(',')
                    .append(csv(r.getLeadName())).append(',')
                    .append(csv(r.getPhone())).append(',')
                    .append(csv(r.getAssignTo())).append(',')
                    .append(r.getDueDate() != null ? r.getDueDate() : "").append(',')
                    .append(r.getSnoozedUntil() != null ? r.getSnoozedUntil() : "").append(',')
                    .append(csv(r.getNotes())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private ReminderResponseDto changeStatus(Long id, ReminderStatus status) {
        Reminder reminder = findOrThrow(id);
        reminder.setStatus(status);
        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder status changed | id: {} | status: {}", id, status);
        return reminderMapper.toDto(saved);
    }

    private Reminder findOrThrow(Long id) {
        Long tenantId = currentTenantId();
        return reminderRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Reminder not found: " + id));
    }

    /**
     * Resolves the supplied lead/assignee publicIds to their internal Long FKs, validating
     * that each exists within the current tenant. Denormalizes display snapshots
     * (lead name, assignee name) so list views never need an extra query. No-op for null
     * publicIds, so partial updates leave existing references untouched.
     */
    private void applyReferences(Reminder reminder, UUID leadPublicId, UUID assignToPublicId, Long tenantId) {
        if (leadPublicId != null) {
            Lead lead = leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(leadPublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + leadPublicId));
            reminder.setLeadRefId(lead.getId());
            reminder.setLeadPublicId(lead.getPublicId());
            reminder.setLeadName(lead.getCustomerName());
        }
        if (assignToPublicId != null) {
            User assignee = userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(assignToPublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + assignToPublicId));
            reminder.setAssignToUserId(assignee.getId());
            reminder.setAssignToPublicId(assignee.getPublicId());
            reminder.setAssignToName(assignee.getName());
        }
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — no tenant bound to this request");
        }
        return tenantId;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new IllegalStateException("Reminders are available to tenant users only");
        }
        return user;
    }

    private Long currentUserId() {
        return currentUser().getId();
    }

    private String currentUsername() {
        return currentUser().getUsername();
    }

    private ReminderStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return ReminderStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ReminderPriority parsePriority(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return ReminderPriority.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ReminderType parseType(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return ReminderType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}