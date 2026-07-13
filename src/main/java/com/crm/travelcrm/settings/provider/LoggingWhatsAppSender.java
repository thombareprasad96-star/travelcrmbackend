package com.crm.travelcrm.settings.provider;

import com.crm.travelcrm.settings.entity.TenantSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link WhatsAppSender}: logs the message instead of calling a real provider. Used when no
 * real provider is active (e.g. {@code app.whatsapp.provider} is not {@code interakt}). When a real
 * provider ({@code InteraktWhatsAppSender}) is present it is {@code @Primary} and transparently takes
 * over — nothing else changes.
 */
@Component
@Slf4j
public class LoggingWhatsAppSender implements WhatsAppSender {

    @Override
    public void send(TenantSettings ts, WaTemplateMessage message) {
        log.info("[WhatsApp STUB] would send template '{}' ({}) to {} with body {}",
                message.templateName(), message.languageCode(), message.toPhoneE164(),
                message.bodyValues());
    }
}