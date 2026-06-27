package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Email delivery — logging stub until an email provider (JavaMailSender/SES/…) is wired. */
@Slf4j
@Component
public class EmailOtpSender implements OtpDeliverySender {

    @Override
    public OtpChannel channel() {
        return OtpChannel.EMAIL;
    }

    @Override
    public void deliver(String destination, String code, OtpPurpose purpose) {
        log.info("[OTP][EMAIL][stub] to={} purpose={} code={} — wire an email provider here",
                destination, purpose, code);
    }
}
