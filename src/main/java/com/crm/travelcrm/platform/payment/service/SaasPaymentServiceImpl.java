package com.crm.travelcrm.platform.payment.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.notification.api.PlatformNotificationType;
import com.crm.travelcrm.platform.notification.api.PlatformNotifyEvent;
import com.crm.travelcrm.platform.payment.dto.PaymentOrderResponse;
import com.crm.travelcrm.platform.payment.entity.PaymentTransaction;
import com.crm.travelcrm.platform.payment.entity.WebhookEvent;
import com.crm.travelcrm.platform.payment.enums.PaymentTransactionStatus;
import com.crm.travelcrm.platform.payment.gateway.GatewayOrder;
import com.crm.travelcrm.platform.payment.gateway.PaymentGatewayClient;
import com.crm.travelcrm.platform.payment.repository.PaymentTransactionRepository;
import com.crm.travelcrm.platform.payment.repository.WebhookEventRepository;
import com.crm.travelcrm.platform.subscription.dunning.DunningService;
import com.crm.travelcrm.platform.subscription.plan.TenantPlanApplier;
import com.crm.travelcrm.platform.subscription.entity.Subscription;
import com.crm.travelcrm.platform.subscription.enums.SubscriptionStatus;
import com.crm.travelcrm.platform.subscription.repository.SubscriptionRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SaaS billing payments over the {@link PaymentGatewayClient}. The live path is Orders /
 * pay-per-invoice: a tenant creates an order for an UNPAID {@code BillingRecord}, pays via Checkout,
 * and the {@code payment.captured} webhook marks the invoice PAID. Recurring {@code subscription.*}
 * events are routed to the {@link Subscription} scaffold.
 *
 * <p>Webhook safety: the HMAC signature is verified before any state changes; processing is idempotent
 * on the gateway event id (unique {@code payment_webhook_events.event_id}); and each handler no-ops if
 * the target is already in the terminal state, so a duplicate delivery cannot double-apply.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaasPaymentServiceImpl implements SaasPaymentService {

    private final PaymentGatewayClient gateway;
    private final TenantRepository tenantRepository;
    private final BillingRecordRepository billingRecordRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DunningService dunningService;
    /** Applies a pay-first upgrade when its tagged invoice is captured. */
    private final TenantPlanApplier tenantPlanApplier;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // ── Tenant: create an order to pay an invoice ────────────────────────────

    // NOT @Transactional: the synchronous Razorpay HTTP call must not run while a pooled DB connection is
    // held, or a gateway latency spike would pin connections across the app. Reads are single-entity finds
    // (no session needed after they return — all fields read are eager columns); the two writes below
    // (PaymentTransaction insert, audit) each run in their own short transaction.
    @Override
    public PaymentOrderResponse createOrderForInvoice(UUID invoicePublicId, Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("No tenant on the current principal.");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        BillingRecord invoice = billingRecordRepository.findByPublicIdAndDeletedAtIsNull(invoicePublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoicePublicId));

        // Object-level ownership: another tenant's invoice must 404, never leak its existence.
        if (!Objects.equals(invoice.getTenantId(), tenantId)) {
            throw new ResourceNotFoundException("Invoice not found: " + invoicePublicId);
        }
        if (invoice.getStatus() == BillingStatus.PAID) {
            throw new BusinessException("This invoice is already paid.");
        }
        if (invoice.getStatus() == BillingStatus.VOID) {
            throw new BusinessException("This invoice has been voided and cannot be paid.");
        }
        if (!gateway.isEnabled()) {
            throw new BusinessException(
                    "Online payment is not enabled yet. Please contact support to settle this invoice.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        BigDecimal amount = invoice.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("This invoice has no amount due.");
        }
        String currency = invoice.getCurrency() != null ? invoice.getCurrency() : "INR";
        // Derive the minor-unit scale from the currency (INR/USD=2, JPY/KRW=0, KWD/BHD=3) — hard-coding
        // ×100 would 100× a JPY charge. Reject a currency the JVM doesn't recognise rather than mis-scale.
        int fractionDigits;
        try {
            fractionDigits = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Unsupported currency: " + currency, HttpStatus.BAD_REQUEST);
        }
        if (fractionDigits < 0) {
            fractionDigits = 2;   // pseudo-currencies (e.g. XAU) have no minor unit — fall back to 2
        }
        long amountMinor = amount.movePointRight(fractionDigits).setScale(0, RoundingMode.HALF_UP).longValueExact();

        GatewayOrder order = gateway.createOrder(amountMinor, currency, invoice.getInvoiceNumber(),
                Map.of("tenantId", String.valueOf(tenantId),
                        "invoicePublicId", invoice.getPublicId().toString(),
                        "invoiceNumber", invoice.getInvoiceNumber()));

        PaymentTransaction txn = paymentTransactionRepository.save(PaymentTransaction.builder()
                .tenantId(tenantId)
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .billingRecordId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .provider(gateway.provider())
                .gatewayOrderId(order.orderId())
                .amount(amount)
                .currency(currency)
                .status(PaymentTransactionStatus.CREATED)
                .notes("Order for invoice " + invoice.getInvoiceNumber())
                .build());

        audit(PlatformAuditAction.PAYMENT_ORDER_CREATED, tenantId, tenant.getOrganizationCode(),
                "PAYMENT", txn.getPublicId(),
                "Created order " + order.orderId() + " (" + currency + " " + amount
                        + ") for " + invoice.getInvoiceNumber());

        return PaymentOrderResponse.builder()
                .transactionPublicId(txn.getPublicId())
                .provider(gateway.provider())
                .gatewayKeyId(gateway.publicKeyId())
                .orderId(order.orderId())
                .amountMinor(order.amountMinor())
                .amount(amount)
                .currency(currency)
                .invoicePublicId(invoice.getPublicId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .tenantName(tenant.getOrganizationName())
                .prefillEmail(tenant.getEmail())
                .prefillContact(tenant.getPhone())
                .build();
    }

    // ── Webhook: verify → idempotent → route ─────────────────────────────────

    @Override
    @Transactional
    public void handleRazorpayWebhook(byte[] rawBody, String signatureHeader, String eventIdHeader) {
        // 1. Signature FIRST — never touch state for an unverified payload.
        if (!gateway.verifyWebhookSignature(rawBody, signatureHeader)) {
            log.warn("[SAAS-PAY] webhook signature verification FAILED — rejecting");
            throw new BusinessException("Invalid webhook signature.", HttpStatus.UNAUTHORIZED);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new BusinessException("Malformed webhook payload.", HttpStatus.BAD_REQUEST);
        }

        String eventType = str(root, "event");
        String eventId = resolveEventId(eventIdHeader, rawBody);

        // 2. Idempotency — the unique event_id is the authoritative guard against a re-delivery.
        if (webhookEventRepository.existsByEventId(eventId)) {
            log.info("[SAAS-PAY] duplicate webhook {} ({}) — already processed, skipping", eventId, eventType);
            return;
        }

        // 3. Route (each handler is internally idempotent).
        String relatedRef = route(eventType, root);

        // 4. Persist the ledger row LAST: a processing failure rolls the whole tx back so a retry
        //    re-processes, and a concurrent duplicate collides here on the unique event_id.
        webhookEventRepository.save(WebhookEvent.builder()
                .provider(gateway.provider())
                .eventId(eventId)
                .eventType(eventType)
                .relatedRef(relatedRef)
                .processed(true)
                .build());
    }

    private String route(String eventType, JsonNode root) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "payment.captured", "order.paid" -> handlePaymentCaptured(root);
            case "payment.failed" -> handlePaymentFailed(root);
            case "subscription.activated", "subscription.charged", "subscription.completed",
                 "subscription.cancelled", "subscription.halted", "subscription.paused" ->
                    handleSubscriptionEvent(eventType, root);
            default -> {
                log.info("[SAAS-PAY] unhandled webhook event: {}", eventType);
                yield null;
            }
        };
    }

    private String handlePaymentCaptured(JsonNode root) {
        JsonNode p = root.path("payload").path("payment").path("entity");
        String orderId = str(p, "order_id");
        String paymentId = str(p, "id");
        if (orderId == null) {
            log.warn("[SAAS-PAY] payment.captured with no order_id (payment {})", paymentId);
            return paymentId;
        }
        PaymentTransaction txn = paymentTransactionRepository.findByGatewayOrderId(orderId).orElse(null);
        if (txn == null) {
            log.warn("[SAAS-PAY] payment.captured for unknown order {} — no local transaction", orderId);
            return orderId;
        }
        if (txn.getStatus() == PaymentTransactionStatus.CAPTURED) {
            return orderId;   // already settled — idempotent no-op
        }

        txn.setGatewayPaymentId(paymentId);
        txn.setStatus(PaymentTransactionStatus.CAPTURED);
        paymentTransactionRepository.save(txn);

        settleInvoice(txn);

        audit(PlatformAuditAction.PAYMENT_CAPTURED, txn.getTenantId(), txn.getTenantCode(),
                "PAYMENT", txn.getPublicId(),
                "Captured " + txn.getCurrency() + " " + txn.getAmount() + " for "
                        + txn.getInvoiceNumber() + " (payment " + paymentId + ")");

        eventPublisher.publishEvent(PlatformNotifyEvent.builder()
                .type(PlatformNotificationType.SAAS_PAYMENT_RECEIVED)
                .title("Payment received")
                .message(txn.getTenantName() + " paid " + txn.getCurrency() + " " + txn.getAmount()
                        + " for invoice " + txn.getInvoiceNumber() + " (payment " + paymentId + ")")
                .referenceType(PlatformNotificationType.REF_BILLING)
                .referencePublicId(txn.getPublicId())
                .tenantId(txn.getTenantId())
                .tenantName(txn.getTenantName())
                .build());

        notifyTenantAdmins(txn.getTenantId(), txn.getPublicId(), "Payment received",
                "We received your payment of " + txn.getCurrency() + " " + txn.getAmount()
                        + " for invoice " + txn.getInvoiceNumber() + ". Thank you!");
        return orderId;
    }

    private String handlePaymentFailed(JsonNode root) {
        JsonNode p = root.path("payload").path("payment").path("entity");
        String orderId = str(p, "order_id");
        if (orderId == null) {
            return str(p, "id");
        }
        PaymentTransaction txn = paymentTransactionRepository.findByGatewayOrderId(orderId).orElse(null);
        if (txn == null || txn.getStatus() == PaymentTransactionStatus.CAPTURED) {
            return orderId;   // unknown, or a late failure after a successful capture — ignore
        }
        txn.setStatus(PaymentTransactionStatus.FAILED);
        txn.setFailureReason(truncate(str(p, "error_description"), 500));
        paymentTransactionRepository.save(txn);

        audit(PlatformAuditAction.PAYMENT_FAILED, txn.getTenantId(), txn.getTenantCode(),
                "PAYMENT", txn.getPublicId(),
                "Payment failed for " + txn.getInvoiceNumber()
                        + (txn.getFailureReason() != null ? " — " + txn.getFailureReason() : ""));

        eventPublisher.publishEvent(PlatformNotifyEvent.builder()
                .type(PlatformNotificationType.SAAS_PAYMENT_FAILED)
                .title("Payment failed")
                .message(txn.getTenantName() + "'s payment of " + txn.getCurrency() + " " + txn.getAmount()
                        + " for invoice " + txn.getInvoiceNumber() + " failed"
                        + (txn.getFailureReason() != null ? " — " + txn.getFailureReason() : ""))
                .referenceType(PlatformNotificationType.REF_BILLING)
                .referencePublicId(txn.getPublicId())
                .tenantId(txn.getTenantId())
                .tenantName(txn.getTenantName())
                .build());

        // Dunning: a failed SaaS payment opens the grace window (ACTIVE → PAST_DUE).
        dunningService.onPaymentFailed(txn.getTenantId());
        return orderId;
    }

    /** Mark the invoice this transaction settles as PAID (idempotent). */
    private void settleInvoice(PaymentTransaction txn) {
        if (txn.getBillingRecordId() == null) {
            return;
        }
        BillingRecord invoice = billingRecordRepository.findById(txn.getBillingRecordId()).orElse(null);
        // Only settle a still-outstanding invoice. Skip one already PAID (duplicate capture) or one that
        // was VOIDed/superseded (e.g. an upgrade order replaced before a stale capture arrived) so a
        // captured-but-voided order can never wrongly settle or apply an upgrade.
        if (invoice == null || invoice.getStatus() != BillingStatus.UNPAID) {
            return;
        }
        invoice.setStatus(BillingStatus.PAID);
        invoice.setPaidDate(LocalDate.now());
        billingRecordRepository.save(invoice);
        // Dunning: paying clears a grace/expiry state — reactivate the tenant once all dues are
        // settled and extend access to this invoice's period end.
        dunningService.onInvoicePaid(invoice.getTenantId(), invoice.getPeriodEnd());
        // Pay-first upgrade: a captured upgrade charge now applies the plan the tenant paid for.
        if (invoice.getUpgradeToPlan() != null) {
            tenantPlanApplier.applyPlan(invoice.getTenantId(), invoice.getUpgradeToPlan(),
                    "Upgraded to " + invoice.getUpgradeToPlan() + " on payment of " + invoice.getInvoiceNumber());
        }
    }

    /** Recurring-subscription scaffold: mirror the gateway state onto a local {@link Subscription}. */
    private String handleSubscriptionEvent(String eventType, JsonNode root) {
        JsonNode s = root.path("payload").path("subscription").path("entity");
        String subId = str(s, "id");
        if (subId == null) {
            log.info("[SAAS-PAY] {} with no subscription id", eventType);
            return null;
        }
        Subscription sub = subscriptionRepository.findByGatewaySubscriptionId(subId).orElse(null);
        if (sub == null) {
            // Expected until the recurring flow is switched on (Orders is the live path today).
            log.info("[SAAS-PAY][scaffold] {} for gateway subscription {} — no local subscription yet",
                    eventType, subId);
            return subId;
        }
        SubscriptionStatus mapped = switch (eventType) {
            case "subscription.activated", "subscription.charged" -> SubscriptionStatus.ACTIVE;
            case "subscription.completed" -> SubscriptionStatus.COMPLETED;
            case "subscription.cancelled" -> SubscriptionStatus.CANCELLED;
            case "subscription.halted" -> SubscriptionStatus.HALTED;
            case "subscription.paused" -> SubscriptionStatus.PAUSED;
            default -> sub.getStatus();
        };
        sub.setStatus(mapped);
        subscriptionRepository.save(sub);

        PlatformAuditAction action = (mapped == SubscriptionStatus.CANCELLED || mapped == SubscriptionStatus.HALTED)
                ? PlatformAuditAction.SUBSCRIPTION_CANCELLED
                : PlatformAuditAction.SUBSCRIPTION_ACTIVATED;
        audit(action, sub.getTenantId(), null, "SUBSCRIPTION", sub.getPublicId(),
                "Subscription " + subId + " → " + mapped + " (" + eventType + ")");
        return subId;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void notifyTenantAdmins(Long tenantId, UUID referencePublicId, String title, String message) {
        List<Long> recipients = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();
        if (recipients.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("SAAS_PAYMENT_RECEIVED")
                .tenantId(tenantId)
                .recipientUserIds(recipients)
                .title(title)
                .message(message)
                .referenceType("BILLING")
                .referencePublicId(referencePublicId)
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    private void audit(PlatformAuditAction action, Long tenantId, String tenantCode,
                       String targetType, UUID publicId, String description) {
        platformAuditRecorder.safeRecord(action, true, tenantId, tenantCode, targetType, publicId, description);
    }

    /** Prefer the gateway's event id header; fall back to a content hash so idempotency still holds. */
    private static String resolveEventId(String eventIdHeader, byte[] rawBody) {
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            return eventIdHeader.trim();
        }
        return "sha256:" + sha256Hex(rawBody);
    }

    private static String str(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return (n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] raw = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
