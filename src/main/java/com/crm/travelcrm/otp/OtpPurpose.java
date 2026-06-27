package com.crm.travelcrm.otp;

/**
 * What an OTP is being used for — namespaces stored codes so unrelated flows never collide and can
 * have independent lifetimes. Add a constant to reuse the module for a new flow (e.g. staff 2FA);
 * nothing else changes (OCP).
 */
public enum OtpPurpose {
    /** Traveler portal login. */
    PORTAL_LOGIN
}
