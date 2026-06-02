package com.crm.travelcrm.otp.service;

import com.crm.travelcrm.otp.dto.OtpRequestDTO;
import com.crm.travelcrm.otp.exception.InvalidOtpException;
import com.crm.travelcrm.otp.exception.OtpExpiredException;
import com.crm.travelcrm.otp.factory.OtpStrategyFactory;
import com.crm.travelcrm.otp.redies.OtpRedisKeyBuilder;
import com.crm.travelcrm.otp.strategy.OtpStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.security.SecureRandom;

@Service
public class OtpServiceImpl implements OtpService {

    private static final Logger logger = LogManager.getLogger(OtpServiceImpl.class);
    private static final int OTP_EXPIRY_MINUTES = 15;

    private final RedisTemplate<String, String> redisTemplate;
    private final OtpStrategyFactory factory;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpServiceImpl(RedisTemplate<String, String> redisTemplate,
                          OtpStrategyFactory factory) {
        this.redisTemplate = redisTemplate;
        this.factory = factory;
    }

    @Override
    public String sendOtp(OtpRequestDTO request) {

        String otp = generateOtp();
        String key = OtpRedisKeyBuilder.build(request);

        redisTemplate.opsForValue().set(
                key,
                otp,
                Duration.ofMinutes(OTP_EXPIRY_MINUTES)
        );

        logger.info("OTP stored in Redis for key: {}", key);

        OtpStrategy strategy = factory.getStrategy(request.getChannel());
        strategy.sendOtp(request.getDestination(), otp);

        return otp;
    }

    @Override
    public boolean verifyOtp(OtpRequestDTO request, String otp) {

        String key = OtpRedisKeyBuilder.build(request);
        String storedOtp = redisTemplate.opsForValue().get(key);

        if (storedOtp == null) {
            throw new OtpExpiredException("OTP expired or not found");
        }

        if (!storedOtp.equals(otp)) {
            throw new InvalidOtpException("Invalid OTP");
        }

        // delete after success (one-time use OTP)
        redisTemplate.delete(key);

        logger.info("OTP verified successfully for key: {}", key);

        return true;
    }

    @Override
    public LocalDateTime getOtpExpiry(int minutes) {
        return LocalDateTime.now().plusMinutes(minutes);
    }

    public String generateOtp() {
        int otp = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(otp);
    }
}