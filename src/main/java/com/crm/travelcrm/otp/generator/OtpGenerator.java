package com.crm.travelcrm.otp.generator;

/** Produces a fresh OTP code. Abstracted so the format (numeric/alphanumeric) can change in one place. */
public interface OtpGenerator {
    String generate(int length);
}
