package com.crm.travelcrm.settings.provider;

import com.crm.travelcrm.settings.entity.TenantSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default {@link WhatsAppSender}: logs the message instead of calling a real provider. This is the
 * only sender today, so it injects by type. When a real provider (Interakt / Meta Cloud API) is
 * added, mark that bean {@code @Primary} and it transparently takes over — nothing else changes.
 */
@Component
@Slf4j
public class LoggingWhatsAppSender implements WhatsAppSender {

    @Override
    public void send(TenantSettings ts, String toPhoneE164, List<String> bodyValues) {
        log.info("[WhatsApp STUB] would send template '{}' ({}) to {} with body {}",
                ts.getWaTemplateName(), ts.getWaTemplateLanguage(), toPhoneE164, bodyValues);
    }
}