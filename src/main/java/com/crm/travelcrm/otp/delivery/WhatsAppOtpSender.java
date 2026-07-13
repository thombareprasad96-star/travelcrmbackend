package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import com.crm.travelcrm.settings.service.WhatsAppMessagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WhatsApp OTP delivery via the tenant's configured provider (Interakt), through the shared
 * {@link WhatsAppMessagingService} facade. The code is passed as the template's first body value.
 *
 * <p>Degrades gracefully: if there is no tenant in context or the tenant hasn't configured WhatsApp,
 * it logs and returns rather than failing the OTP request. The code itself is never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppOtpSender implements OtpDeliverySender {

    private final WhatsAppMessagingService whatsAppMessaging;

    @Override
    public OtpChannel channel() {
        return OtpChannel.WHATSAPP;
    }

    @Override
    public void deliver(String destination, String code, OtpPurpose purpose) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || !whatsAppMessaging.isConfigured(tenantId)) {
            log.info("[OTP][WHATSAPP] to={} purpose={} — WhatsApp not configured for tenant; skipping send",
                    destination, purpose);
            return;
        }
        WhatsAppMessagingService.Result result = whatsAppMessaging.sendPurpose(
                tenantId, WhatsAppMessagingService.Purpose.OTP, destination, List.of(code));
        if (!result.success()) {
            log.warn("[OTP][WHATSAPP] send failed to {} (purpose={}): {}",
                    destination, purpose, result.errorMessage());
        }
    }
}
