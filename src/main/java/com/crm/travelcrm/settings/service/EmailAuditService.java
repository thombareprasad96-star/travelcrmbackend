package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.settings.dto.EmailStatsDTO;
import com.crm.travelcrm.settings.entity.EmailMessageLog;
import com.crm.travelcrm.settings.repository.EmailMessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Records email send attempts and reports send stats. {@link #record} runs in its own
 * ({@code REQUIRES_NEW}) transaction so the log persists even when the caller's transaction is
 * read-only (e.g. the quotation email flow) or later rolls back.
 */
@Service
@RequiredArgsConstructor
public class EmailAuditService {

    private final EmailMessageLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String recipient, String subject, boolean success, String error) {
        // tenantId auto-stamped by TenantEntityListener from the current TenantContext.
        repo.save(EmailMessageLog.builder()
                .recipient(recipient)
                .subject(subject)
                .status(success ? "SENT" : "FAILED")
                .errorMsg(error)
                .sentAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public EmailStatsDTO stats(Long tenantId) {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long sent   = repo.countByTenantIdAndStatusAndSentAtAfter(tenantId, "SENT", dayStart);
        long failed = repo.countByTenantIdAndStatusAndSentAtAfter(tenantId, "FAILED", dayStart);
        return new EmailStatsDTO(sent, failed);
    }
}