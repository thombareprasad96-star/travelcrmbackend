package com.crm.travelcrm.otp;

/** Delivery channel for an OTP. {@link #AUTO} infers SMS vs EMAIL from the destination. */
public enum OtpChannel {
    SMS,
    EMAIL,
    WHATSAPP,
    AUTO
}
