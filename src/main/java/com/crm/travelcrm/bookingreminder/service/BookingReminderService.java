package com.crm.travelcrm.bookingreminder.service;

import com.crm.travelcrm.bookingreminder.dto.BookingReminderRequestDto;
import com.crm.travelcrm.bookingreminder.dto.BookingReminderResponseDto;
import com.crm.travelcrm.bookingreminder.dto.BookingReminderStatsDto;
import com.crm.travelcrm.bookingreminder.entity.BookingReminder;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderStatus;
import com.crm.travelcrm.bookingreminder.repository.BookingReminderRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingReminderService {

    private final BookingReminderRepository repository;

    // ── Queries ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookingReminderResponseDto> getAll(String status) {
        Long tenantId = currentTenantId();
        List<BookingReminder> rows = (status == null || status.isBlank())
                ? repository.findByTenantIdAndDeletedAtIsNullOrderByReminderDateDesc(tenantId)
                : repository.findByTenantIdAndStatusAndDeletedAtIsNullOrderByReminderDateDesc(
                        tenantId, parseStatus(status));
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BookingReminderResponseDto getById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<BookingReminderResponseDto> getByBookingCode(String bookingCode) {
        return repository
                .findByTenantIdAndBookingCodeAndDeletedAtIsNullOrderByReminderDateDesc(currentTenantId(), bookingCode)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<BookingReminderResponseDto> getUpcoming(int days) {
        Instant now = Instant.now();
        Instant until = now.plus(days, ChronoUnit.DAYS);
        return repository
                .findByTenantIdAndDeletedAtIsNullAndTravelDateBetweenOrderByTravelDateAsc(currentTenantId(), now, until)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BookingReminderStatsDto getStats() {
        Long tenantId = currentTenantId();
        return BookingReminderStatsDto.builder()
                .total(repository.countByTenantIdAndDeletedAtIsNull(tenantId))
                .pending(repository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, BookingReminderStatus.Pending))
                .sent(repository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, BookingReminderStatus.Sent))
                .completed(repository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, BookingReminderStatus.Completed))
                .build();
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    @Transactional
    public BookingReminderResponseDto create(BookingReminderRequestDto req) {
        BookingReminder r = BookingReminder.builder()
                .bookingCode(req.getBookingCode().trim())
                .customerName(req.getCustomerName().trim())
                .phone(req.getPhone())
                .destination(req.getDestination())
                .reminderType(req.getReminderType())
                .message(req.getMessage())
                .travelDate(req.getTravelDate())
                .reminderDate(req.getReminderDate())
                .status(req.getStatus() != null ? req.getStatus() : BookingReminderStatus.Pending)
                .amount(req.getAmount())
                .build();
        BookingReminder saved = repository.save(r);   // tenant_id auto-stamped
        log.info("BookingReminder created | id: {} | code: {}", saved.getId(), saved.getBookingCode());
        return toDto(saved);
    }

    @Transactional
    public BookingReminderResponseDto update(Long id, BookingReminderRequestDto req) {
        BookingReminder r = findOrThrow(id);
        r.setBookingCode(req.getBookingCode().trim());
        r.setCustomerName(req.getCustomerName().trim());
        r.setPhone(req.getPhone());
        r.setDestination(req.getDestination());
        r.setReminderType(req.getReminderType());
        r.setMessage(req.getMessage());
        r.setTravelDate(req.getTravelDate());
        r.setReminderDate(req.getReminderDate());
        if (req.getStatus() != null) {
            r.setStatus(req.getStatus());
        }
        r.setAmount(req.getAmount());
        return toDto(repository.save(r));
    }

    @Transactional
    public void delete(Long id) {
        BookingReminder r = findOrThrow(id);
        r.softDelete(currentUsername());
        repository.save(r);
        log.info("BookingReminder soft-deleted | id: {}", id);
    }

    @Transactional
    public BookingReminderResponseDto updateStatus(Long id, BookingReminderStatus status) {
        BookingReminder r = findOrThrow(id);
        r.setStatus(status);
        return toDto(repository.save(r));
    }

    /**
     * Dispatch placeholder: real WhatsApp/SMS/Email sending is not wired yet (Twilio
     * credentials are placeholders), so this just marks the reminder as Sent.
     */
    @Transactional
    public BookingReminderResponseDto sendNow(Long id) {
        BookingReminder r = findOrThrow(id);
        r.setStatus(BookingReminderStatus.Sent);
        log.info("BookingReminder send-now (stub dispatch) | id: {} | code: {}", id, r.getBookingCode());
        return toDto(repository.save(r));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BookingReminder findOrThrow(Long id) {
        return repository.findByIdAndTenantIdAndDeletedAtIsNull(id, currentTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Booking reminder not found: " + id));
    }

    private BookingReminderStatus parseStatus(String status) {
        try {
            return BookingReminderStatus.valueOf(status.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid status: " + status, HttpStatus.BAD_REQUEST);
        }
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("No tenant context. Authenticate with a tenant user token.",
                    HttpStatus.UNAUTHORIZED);
        }
        return tenantId;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private BookingReminderResponseDto toDto(BookingReminder r) {
        return BookingReminderResponseDto.builder()
                .id(r.getId())
                .bookingCode(r.getBookingCode())
                .customerName(r.getCustomerName())
                .phone(r.getPhone())
                .destination(r.getDestination())
                .reminderType(r.getReminderType())
                .message(r.getMessage())
                .travelDate(r.getTravelDate())
                .reminderDate(r.getReminderDate())
                .status(r.getStatus())
                .amount(r.getAmount())
                .createdAt(r.getCreatedAt())
                .build();
    }
}