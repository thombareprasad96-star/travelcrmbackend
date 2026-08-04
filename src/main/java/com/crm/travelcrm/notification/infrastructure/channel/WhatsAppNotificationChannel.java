package com.crm.travelcrm.notification.infrastructure.channel;

import com.crm.travelcrm.auth.api.UserDirectory;
import com.crm.travelcrm.common.ratelimit.RateLimitService;
import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.api.TemplateRenderer;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.settings.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Delivers a notification to a staff member's WhatsApp through the tenant's own provider account.
 *
 * <p>This is the channel the enum's {@code // Future: … WHATSAPP …} comment always anticipated: the
 * dispatcher is untouched, {@code NotifyEventListener} discovers this bean automatically, and any
 * publisher can now add {@code DeliveryChannel.WHATSAPP} to its channel set.
 *
 * <h2>Template-only, and that constrains the payload</h2>
 * The provider accepts free text only inside a 24-hour customer session window, which never applies
 * to an outbound staff alert. So the event's title and message are passed as <b>ordered template
 * body values</b> — {@code {{1}} = title}, {@code {{2}} = message} — against a pre-approved template.
 * The template must exist and be approved provider-side with exactly that arity, or the send is
 * rejected. The name is resolved config-first
 * ({@code app.whatsapp.templates.task-alert}) and falls back to the tenant's default template.
 *
 * <h2>Three things this deliberately does NOT inherit</h2>
 * <ol>
 *   <li><b>Rate limiting.</b> {@code WhatsAppMessagingService} applies none — only the marketing
 *       campaign batcher does, and it does so itself. A scheduled sweep that alerts every assignee
 *       in a tenant would otherwise hit the provider unthrottled and risk the agency's number being
 *       flagged. The per-tenant window below is applied here, at the only place that knows this is
 *       a fan-out.</li>
 *   <li><b>Retries.</b> The sender makes exactly one attempt and records the outcome in
 *       {@code whatsapp_logs}. A failed alert is lost, not queued — there is no outbox in this
 *       product yet. That is a known limitation, recorded in docs/ALL_TASKS_MODULE_DESIGN.md, not an
 *       oversight here: adding a retry loop on a synchronous dispatcher thread would make it worse.</li>
 *   <li><b>Delivery confirmation.</b> Provider status callbacks are not consumed anywhere, so
 *       "sent" means "accepted by the provider", never "read by the agent".</li>
 * </ol>
 *
 * <p>A tenant with no WhatsApp credentials is skipped at debug level — the other channels on the
 * same event still deliver, which is the whole point of the dispatcher's per-channel isolation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppNotificationChannel implements NotificationChannel {

    private final WhatsAppMessagingService whatsAppMessagingService;
    private final UserDirectory userDirectory;
    private final TemplateRenderer templateRenderer;
    private final RateLimitService rateLimitService;

    /** Approved provider template for staff alerts. Blank ⇒ the tenant's default template is used. */
    @Value("${app.whatsapp.templates.task-alert:}")
    private String taskAlertTemplate;

    /** Messages per tenant per minute. Conservative — these are staff alerts, not a campaign. */
    @Value("${app.whatsapp.notification.rate.per-minute:30}")
    private int ratePerMinute;

    @Override
    public DeliveryChannel channelType() {
        return DeliveryChannel.WHATSAPP;
    }

    @Override
    public void send(NotifyEvent event, Notification notification) {
        Long tenantId = event.getTenantId();
        if (tenantId == null) {
            log.debug("WhatsApp notification skipped: event has no tenantId");
            return;
        }
        if (!whatsAppMessagingService.isConfigured(tenantId)) {
            log.debug("WhatsApp notification skipped for tenant {}: no WhatsApp credentials configured",
                    tenantId);
            return;
        }
        List<Long> recipients = event.getRecipientUserIds();
        if (recipients == null || recipients.isEmpty()) {
            // Unlike the in-app channel there is NO admin fallback here on purpose: WhatsApp is a
            // personal, interrupting channel, and broadcasting an unaddressed event to every admin
            // is the wrong default. A publisher that wants WhatsApp must name its recipients.
            log.debug("WhatsApp notification skipped for tenant {}: no explicit recipients", tenantId);
            return;
        }

        Map<String, Object> payload = event.getPayload() != null ? event.getPayload() : Map.of();
        String title = templateRenderer.render(event.getTitle(), payload);
        String body = templateRenderer.render(
                event.getMessage() != null ? event.getMessage() : event.getTitle(), payload);

        String rateKey = "notify:whatsapp:" + tenantId;

        for (Long recipientId : recipients) {
            if (!rateLimitService.isAllowed(rateKey, ratePerMinute, Duration.ofMinutes(1))) {
                // Drop the remainder rather than queue it: this is a per-minute sweep, and the next
                // tick re-derives who is still overdue. Queuing would double-send.
                log.warn("WhatsApp notification rate limit hit for tenant {} — {} recipient(s) skipped this tick",
                        tenantId, recipients.size() - recipients.indexOf(recipientId));
                return;
            }
            userDirectory.phoneById(recipientId).ifPresentOrElse(
                    phone -> dispatch(tenantId, phone, title, body, recipientId),
                    () -> log.debug("WhatsApp notification skipped for user {}: no phone number on file",
                            recipientId));
        }
    }

    private void dispatch(Long tenantId, String phone, String title, String body, Long recipientId) {
        WhatsAppMessagingService.Result result = StringUtils.hasText(taskAlertTemplate)
                ? whatsAppMessagingService.sendTemplate(tenantId, phone, taskAlertTemplate, List.of(title, body))
                : whatsAppMessagingService.sendWithTenantTemplate(tenantId, phone, List.of(title, body));

        if (result.success()) {
            log.debug("WhatsApp notification sent to user {}", recipientId);
        } else {
            // Logged, not thrown: the dispatcher's per-channel try/catch would swallow it anyway,
            // and a WhatsApp failure must never cost the user their in-app notification.
            log.warn("WhatsApp notification failed for user {}: {}", recipientId, result.errorMessage());
        }
    }
}
