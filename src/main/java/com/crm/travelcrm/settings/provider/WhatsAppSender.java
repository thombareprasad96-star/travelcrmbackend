package com.crm.travelcrm.settings.provider;

import com.crm.travelcrm.settings.entity.TenantSettings;

import java.util.List;

/**
 * SPI for delivering a WhatsApp template message. The default {@link LoggingWhatsAppSender} is a
 * no-op stub (logs only). Drop in a real provider bean (Interakt / Meta Cloud API) marked
 * {@code @Primary} to take over — nothing else in the settings module changes.
 *
 * <p>The provider reads the tenant's own credentials/template from {@code ts}; the caller has
 * already decrypted nothing — implementations decrypt the stored API key themselves via the
 * injected cipher if needed.
 */
public interface WhatsAppSender {

    /**
     * Deliver one templated message using the tenant's credentials.
     *
     * @param ts      the tenant's settings (contains the encrypted API key + phone)
     * @param message the template, destination and body substitutions to send
     * @throws RuntimeException if delivery fails (caller logs FAILED + returns a structured error)
     */
    void send(TenantSettings ts, WaTemplateMessage message);

    /**
     * Convenience overload that sends using the tenant's own default template
     * (Settings → WhatsApp) — used by the "Send test" button.
     *
     * @param toPhoneE164 destination in E.164, e.g. "+919099097103"
     * @param bodyValues  ordered template body substitutions ({{1}}, {{2}}, …)
     */
    default void send(TenantSettings ts, String toPhoneE164, List<String> bodyValues) {
        send(ts, new WaTemplateMessage(toPhoneE164, ts.getWaTemplateName(),
                ts.getWaTemplateLanguage(), bodyValues, ts.getWaHeaderImageUrl()));
    }
}