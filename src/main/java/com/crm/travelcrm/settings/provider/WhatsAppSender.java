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
     * @param ts          the tenant's settings (contains encrypted API key, template name/lang)
     * @param toPhoneE164 destination in E.164, e.g. "+919099097103"
     * @param bodyValues  ordered template body substitutions ({{1}}, {{2}}, …)
     * @throws RuntimeException if delivery fails (caller logs FAILED + returns a structured error)
     */
    void send(TenantSettings ts, String toPhoneE164, List<String> bodyValues);
}