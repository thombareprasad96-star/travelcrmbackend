package com.crm.travelcrm.otp.generator;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Default generator: cryptographically-random numeric code of the requested length. */
@Component
public class NumericOtpGenerator implements OtpGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
