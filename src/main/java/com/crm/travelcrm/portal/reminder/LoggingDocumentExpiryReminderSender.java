package com.crm.travelcrm.portal.reminder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default document-expiry reminder delivery: logs. Stub until a real channel is wired (the
 * notification module's {@code NotifyEvent} is the natural target — a tenant-admin/customer
 * in-app + email reminder). Replace/override this bean to dispatch for real.
 */
@Slf4j
@Component
public class LoggingDocumentExpiryReminderSender implements DocumentExpiryReminderSender {

    @Override
    public void send(DocumentExpiryReminder r) {
        log.info("[PORTAL-DOC-EXPIRY][stub] tenant={} customer={} {} (doc {}) expires {} in {} day(s) "
                        + "— firing {}-day reminder. Wire notification/SMS/email dispatch here.",
                r.tenantId(), r.customerId(), r.type(), r.documentPublicId(),
                r.expiryDate(), r.daysUntilExpiry(), r.thresholdDays());
    }
}
