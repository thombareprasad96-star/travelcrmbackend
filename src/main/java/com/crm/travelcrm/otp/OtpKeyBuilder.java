package com.crm.travelcrm.otp;

/** Builds the namespaced store key for an OTP. One place so request and verify always agree. */
public final class OtpKeyBuilder {

    private OtpKeyBuilder() {}

    public static String build(OtpPurpose purpose, String destination) {
        String normalized = destination == null ? "" : destination.trim().toLowerCase();
        return "otp:" + purpose.name() + ":" + normalized;
    }
}
