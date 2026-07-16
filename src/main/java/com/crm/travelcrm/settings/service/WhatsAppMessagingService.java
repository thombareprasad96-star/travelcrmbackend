package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.settings.config.WhatsAppProperties;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.entity.WaMessageLog;
import com.crm.travelcrm.settings.provider.WaTemplateMessage;
import com.crm.travelcrm.settings.provider.WhatsAppSender;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import com.crm.travelcrm.settings.repository.WaMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Application-facing facade for sending outbound WhatsApp messages. Business modules (OTP, quotation,
 * booking reminders, the settings "test" button) call this instead of touching the {@link WhatsAppSender}
 * SPI directly, so tenant-settings lookup, the "is it configured?" guard, phone normalisation and the
 * {@code whatsapp_logs} audit trail all live in one place.
 *
 * <p>The actual transport is the swappable {@link WhatsAppSender} (Interakt in prod, a logging stub
 * otherwise). Every attempt is recorded — SENT or FAILED — which feeds the integration stats.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppMessagingService {

    private final TenantSettingsRepository settingsRepo;
    private final WaMessageLogRepository logRepo;
    private final WhatsAppSender sender;
    private final WhatsAppProperties props;

    /** Business flow a message belongs to — selects the configured template (falls back to the tenant's). */
    public enum Purpose { OTP, QUOTATION, REMINDER }

    /** Outcome of a send attempt. {@code errorMessage} is populated only on failure. */
    public record Result(boolean success, String errorMessage) {
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    /** True when this tenant has saved a WhatsApp API key (i.e. real sending is possible). */
    @Transactional(readOnly = true)
    public boolean isConfigured(Long tenantId) {
        return loadConfigured(tenantId) != null;
    }

    /**
     * Send using the tenant's own default template (Settings → WhatsApp). Backs the "Send test" button.
     */
    @Transactional
    public Result sendWithTenantTemplate(Long tenantId, String toPhone, List<String> bodyValues) {
        TenantSettings ts = loadConfigured(tenantId);
        if (ts == null) {
            return Result.fail("WhatsApp is not configured for this tenant.");
        }
        return dispatch(ts, toPhone, ts.getWaTemplateName(), ts.getWaTemplateLanguage(),
                bodyValues, ts.getWaHeaderImageUrl());
    }

    /**
     * Send using an explicit template name (falling back to the tenant's default when blank). Generic
     * entry point for flows outside the fixed {@link Purpose} set — e.g. Marketing campaigns / drips /
     * auto-triggers, which choose the template per message. Reuses the same normalisation + audit
     * pipeline; existing callers are unaffected.
     */
    @Transactional
    public Result sendTemplate(Long tenantId, String toPhone, String templateName, List<String> bodyValues) {
        TenantSettings ts = loadConfigured(tenantId);
        if (ts == null) {
            return Result.fail("WhatsApp is not configured for this tenant.");
        }
        String template = StringUtils.hasText(templateName) ? templateName : ts.getWaTemplateName();
        if (!StringUtils.hasText(template)) {
            return Result.fail("No WhatsApp template configured. Set a default template in Settings → WhatsApp.");
        }
        return dispatch(ts, toPhone, template, ts.getWaTemplateLanguage(), bodyValues, ts.getWaHeaderImageUrl());
    }

    /**
     * Send a purpose-specific template (OTP / quotation / reminder). The template name comes from
     * {@code app.whatsapp.templates.*} when set, otherwise falls back to the tenant's default template.
     */
    @Transactional
    public Result sendPurpose(Long tenantId, Purpose purpose, String toPhone, List<String> bodyValues) {
        TenantSettings ts = loadConfigured(tenantId);
        if (ts == null) {
            return Result.fail("WhatsApp is not configured for this tenant.");
        }
        String template = resolveTemplate(purpose, ts);
        if (!StringUtils.hasText(template)) {
            return Result.fail("No WhatsApp template configured for " + purpose
                    + ". Set app.whatsapp.templates." + purpose.name().toLowerCase()
                    + " or a default template in Settings → WhatsApp.");
        }
        return dispatch(ts, toPhone, template, ts.getWaTemplateLanguage(), bodyValues, null);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private TenantSettings loadConfigured(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        TenantSettings ts = settingsRepo.findByTenantId(tenantId).orElse(null);
        return (ts != null && StringUtils.hasText(ts.getWhatsAppApiKeyEnc())) ? ts : null;
    }

    private String resolveTemplate(Purpose purpose, TenantSettings ts) {
        String configured = switch (purpose) {
            case OTP -> props.getTemplates().getOtp();
            case QUOTATION -> props.getTemplates().getQuotation();
            case REMINDER -> props.getTemplates().getReminder();
        };
        return StringUtils.hasText(configured) ? configured : ts.getWaTemplateName();
    }

    private Result dispatch(TenantSettings ts, String rawPhone, String template, String language,
                            List<String> bodyValues, String mediaUrl) {
        String e164 = normalize(rawPhone);
        String lang = StringUtils.hasText(language) ? language : props.getDefaultLanguage();
        try {
            sender.send(ts, new WaTemplateMessage(e164, template, lang, bodyValues, mediaUrl));
            record(e164, template, "SENT", null);
            return Result.ok();
        } catch (Exception ex) {
            log.warn("WhatsApp send failed (template={}, to={}): {}", template, e164, ex.getMessage());
            record(e164, template, "FAILED", ex.getMessage());
            return Result.fail(ex.getMessage());
        }
    }

    private void record(String phone, String template, String status, String error) {
        // tenant_id is auto-stamped by TenantEntityListener from the active TenantContext.
        logRepo.save(WaMessageLog.builder()
                .phone(phone).template(template).status(status).errorMsg(error)
                .sentAt(LocalDateTime.now()).build());
    }

    /** Normalise any national/E.164 input to E.164 using the configured default country code. */
    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9+]", "");
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        cleaned = cleaned.replaceFirst("^0+", "");
        return props.getDefaultCountryCode() + cleaned;
    }
}
