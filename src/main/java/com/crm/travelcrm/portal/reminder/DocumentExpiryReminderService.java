package com.crm.travelcrm.portal.reminder;

import com.crm.travelcrm.portal.document.dto.ExpiringDocumentView;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Per-tenant document-expiry reminder logic. Idempotent: each document stores the smallest
 * day-threshold already notified, and {@link #pickThreshold} only ever returns a threshold strictly
 * smaller than that — so 60/30/7 each fire at most once as expiry approaches, even if the job runs
 * daily or catches up after a gap. Reminders are marked only after a successful send (within the
 * tx), so a failed send simply retries next run (at-least-once).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentExpiryReminderService {

    private final TravelerDocumentRepository documentRepository;
    private final DocumentExpiryReminderSender sender;

    @Transactional
    public int remindForCurrentTenant(Long tenantId, List<Integer> thresholdsDesc, LocalDate today) {
        int maxThreshold = thresholdsDesc.get(0);                 // largest, e.g. 60
        LocalDate maxExpiry = today.plusDays(maxThreshold);

        List<ExpiringDocumentView> candidates = documentRepository
                .findByTenantIdAndDeletedAtIsNullAndExpiryDateIsNotNullAndExpiryDateLessThanEqual(
                        tenantId, maxExpiry);

        int fired = 0;
        for (ExpiringDocumentView doc : candidates) {
            long daysUntil = ChronoUnit.DAYS.between(today, doc.getExpiryDate());
            Integer threshold = pickThreshold(thresholdsDesc, daysUntil, doc.getLastReminderDayThreshold());
            if (threshold == null) continue;
            try {
                sender.send(new DocumentExpiryReminder(doc.getPublicId(), doc.getCustomerId(),
                        tenantId, doc.getType(), doc.getExpiryDate(), daysUntil, threshold));
                documentRepository.markReminded(doc.getId(), threshold);   // idempotency marker
                fired++;
            } catch (Exception e) {
                log.error("Doc-expiry reminder failed for document {} (tenant {}): {}",
                        doc.getPublicId(), tenantId, e.getMessage());
            }
        }
        return fired;
    }

    /**
     * The smallest (most urgent) threshold that expiry has crossed and that hasn't been notified yet,
     * or {@code null} if none applies. Iterating largest→smallest and overwriting yields the minimum
     * crossed-and-unfired threshold (so a long gap fires only the most urgent one, not a backlog).
     */
    private Integer pickThreshold(List<Integer> thresholdsDesc, long daysUntil, Integer lastFired) {
        Integer chosen = null;
        for (int t : thresholdsDesc) {
            if (daysUntil <= t && (lastFired == null || t < lastFired)) {
                chosen = t;
            }
        }
        return chosen;
    }
}
