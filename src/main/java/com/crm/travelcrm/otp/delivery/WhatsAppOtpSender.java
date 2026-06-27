package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** WhatsApp delivery — logging stub until the WhatsApp Business API is wired. */
@Slf4j
@Component
public class WhatsAppOtpSender implements OtpDeliverySender {

    @Override
    public OtpChannel channel() {
        return OtpChannel.WHATSAPP;
    }

    @Override
    public void deliver(String destination, String code, OtpPurpose purpose) {
        log.info("[OTP][WHATSAPP][stub] to={} purpose={} code={} — wire the WhatsApp API here",
                destination, purpose, code);
    }
}
