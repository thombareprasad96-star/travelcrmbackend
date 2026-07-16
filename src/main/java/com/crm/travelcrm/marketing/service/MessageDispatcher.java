package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;
import com.crm.travelcrm.settings.service.TenantMailSenderFactory;
import com.crm.travelcrm.settings.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.mail.internet.MimeMessage;
import java.util.List;

/**
 * The single delivery path shared by campaign, drip and automation sends. Resolves merge tags,
 * checks channel reachability, and hands off to the existing tenant senders
 * ({@link WhatsAppMessagingService} / {@link TenantMailSenderFactory}). Returns a per-recipient
 * outcome; it never throws for a delivery failure (that becomes {@code FAILED}).
 *
 * <p>Deliberately has NO knowledge of campaigns/drips/rate limits — batching + throttling live in
 * the callers, so this stays a pure "deliver one message" collaborator (SRP).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageDispatcher {

    private final WhatsAppMessagingService whatsAppMessagingService;
    private final TenantMailSenderFactory mailSenderFactory;
    private final MergeTagResolver mergeTagResolver;

    /** Outcome of a single delivery attempt. */
    public record DispatchResult(RecipientStatus status, String destination, String error) {
        public boolean sent() { return status == RecipientStatus.SENT; }
    }

    public DispatchResult deliver(Long tenantId, MarketingChannel channel, Customer customer,
                                  String subject, String body, String templateName) {
        return channel == MarketingChannel.EMAIL
                ? email(tenantId, customer, subject, body)
                : whatsapp(tenantId, customer, body, templateName);
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────────

    private DispatchResult whatsapp(Long tenantId, Customer customer, String body, String templateName) {
        String phone = customer.getPhone();
        if (!StringUtils.hasText(phone)) {
            return new DispatchResult(RecipientStatus.SKIPPED, null, "No phone number");
        }
        String resolved = mergeTagResolver.resolve(body, customer);
        WhatsAppMessagingService.Result r =
                whatsAppMessagingService.sendTemplate(tenantId, phone, templateName, List.of(resolved));
        return r.success()
                ? new DispatchResult(RecipientStatus.SENT, phone, null)
                : new DispatchResult(RecipientStatus.FAILED, phone, r.errorMessage());
    }

    // ── Email ────────────────────────────────────────────────────────────────────

    private DispatchResult email(Long tenantId, Customer customer, String subject, String body) {
        String to = customer.getEmail();
        if (!StringUtils.hasText(to)) {
            return new DispatchResult(RecipientStatus.SKIPPED, null, "No email address");
        }
        String resolvedSubject = mergeTagResolver.resolve(subject == null ? "" : subject, customer);
        String resolvedBody = mergeTagResolver.resolve(body, customer);
        try {
            TenantMailSenderFactory.ResolvedMail mail = mailSenderFactory.resolve(tenantId);
            MimeMessage message = mail.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(to);
            helper.setSubject(StringUtils.hasText(resolvedSubject) ? resolvedSubject : "A message from us");
            helper.setText(resolvedBody, true);   // HTML body
            if (StringUtils.hasText(mail.fromName())) {
                helper.setFrom(mail.from(), mail.fromName());
            } else {
                helper.setFrom(mail.from());
            }
            mail.sender().send(message);
            return new DispatchResult(RecipientStatus.SENT, to, null);
        } catch (Exception e) {
            log.warn("Marketing email send failed (to={}): {}", to, e.getMessage());
            return new DispatchResult(RecipientStatus.FAILED, to, e.getMessage());
        }
    }
}