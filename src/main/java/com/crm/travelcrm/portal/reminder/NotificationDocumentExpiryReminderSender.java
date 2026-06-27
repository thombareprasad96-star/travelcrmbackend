package com.crm.travelcrm.portal.reminder;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Real document-expiry reminder delivery: raises a {@link NotifyEvent} so the tenant's
 * admins/managers get an in-app notification ("&lt;customer&gt;'s passport expires on …") and can
 * nudge the customer. {@code @Primary} so it is the {@link DocumentExpiryReminderSender} injected by
 * the scheduler service, replacing the logging stub (which stays as a fallback).
 *
 * <p>Runs inside the per-tenant reminder transaction (tenant set on context), so the customer lookup
 * is tenant-scoped. The notification references the CUSTOMER by publicId so clicking it opens that
 * customer in the staff app. (A traveler-facing SMS/email reminder is a separate channel — wire it
 * by overriding/adding a sender.)</p>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class NotificationDocumentExpiryReminderSender implements DocumentExpiryReminderSender {

    private final ApplicationEventPublisher eventPublisher;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;

    @Override
    public void send(DocumentExpiryReminder r) {
        List<Long> recipientIds = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(r.tenantId(), List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();
        if (recipientIds.isEmpty()) {
            log.info("[PORTAL-DOC-EXPIRY] no admin/manager recipients for tenant {} — skipping", r.tenantId());
            return;
        }

        Customer customer = customerRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(r.customerId(), r.tenantId())
                .orElse(null);
        String name = customer != null ? customer.getName() : "A customer";

        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("DOCUMENT_EXPIRY")
                .tenantId(r.tenantId())
                .recipientUserIds(recipientIds)
                .title(name + "'s " + label(r) + " is expiring")
                .message(name + "'s " + label(r) + " expires on " + r.expiryDate()
                        + " (in " + r.daysUntilExpiry() + " day(s)).")
                .referenceType("CUSTOMER")
                .referencePublicId(customer != null ? customer.getPublicId() : null)
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());

        log.info("[PORTAL-DOC-EXPIRY] notified {} staff: customer {} {} expiring in {} day(s)",
                recipientIds.size(), r.customerId(), r.type(), r.daysUntilExpiry());
    }

    private String label(DocumentExpiryReminder r) {
        return r.type().name().toLowerCase();   // "passport" / "visa" / …
    }
}
