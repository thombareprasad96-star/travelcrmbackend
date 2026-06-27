package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** SMS delivery — logging stub until an SMS gateway (Twilio/MSG91/…) is wired. */
@Slf4j
@Component
public class SmsOtpSender implements OtpDeliverySender {

    @Override
    public OtpChannel channel() {
        return OtpChannel.SMS;
    }

    @Override
    public void deliver(String destination, String code, OtpPurpose purpose) {
        log.info("[OTP][SMS][stub] to={} purpose={} code={} — wire an SMS gateway here",
                destination, purpose, code);
    }
}
