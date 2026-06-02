package com.crm.travelcrm.otp.redies;

import com.crm.travelcrm.auth.enums.OtpChannel;
import com.crm.travelcrm.otp.dto.OtpRequestDTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OtpRedisKeyBuilder {

    private static final Logger logger = LogManager.getLogger(OtpRedisKeyBuilder.class);

    private static final String PREFIX = "otp";

    // MAIN METHOD (used in service)
    public static String build(OtpRequestDTO request) {

        logger.trace("Entered build(OtpRequestDTO)");
        logger.debug("Building OTP key for destination: {}, channel: {}, purpose: register",
                request.getDestination(), request.getChannel());

        String key = build(request.getChannel(), request.getDestination(), "register");

        logger.info("OTP Redis key generated: {}", key);
        logger.trace("Exiting build(OtpRequestDTO)");

        return key;
    }

    // OVERLOADED METHOD (reusable for login/forgot-password etc.)
    public static String build(OtpChannel channel, String destination, String purpose) {

        logger.trace("Entered build(channel, destination, purpose)");
        logger.debug("Input -> channel: {}, destination: {}, purpose: {}",
                channel, destination, purpose);

        String key = PREFIX + ":"
                + purpose + ":"
                + channel + ":"
                + normalize(destination);

        logger.info("Generated OTP key: {}", key);
        logger.trace("Exiting build(channel, destination, purpose)");

        return key;
    }

    // FOR VERIFY / DIRECT USE CASES
    public static String buildVerifyKey(OtpChannel channel, String destination) {

        logger.trace("Entered buildVerifyKey()");
        logger.debug("Building verify key for destination: {}, channel: {}",
                destination, channel);

        String key = PREFIX + ":verify:"
                + channel + ":"
                + normalize(destination);

        logger.info("Generated OTP verify key: {}", key);
        logger.trace("Exiting buildVerifyKey()");

        return key;
    }

    private static String normalize(String value) {

        logger.trace("Normalizing value: {}", value);

        String result = value.trim().toLowerCase();

        logger.debug("Normalized value: {}", result);

        return result;
    }
}