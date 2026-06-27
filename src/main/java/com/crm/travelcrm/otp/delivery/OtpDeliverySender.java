package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;

/**
 * Strategy for delivering a code over one channel. Each implementation declares the {@link OtpChannel}
 * it handles; {@link OtpSenderResolver} routes to it. Add a real provider by dropping in a new bean
 * (or replacing a stub) — the service is untouched (OCP).
 */
public interface OtpDeliverySender {

    OtpChannel channel();

    void deliver(String destination, String code, OtpPurpose purpose);
}
