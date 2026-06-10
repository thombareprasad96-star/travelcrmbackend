package com.crm.travelcrm.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RateLimitService {



    private final Map<String, RequestInfo> requests = new ConcurrentHashMap<>();

    public boolean isAllowed(String key, int maxRequests, Duration window) {

        long now = System.currentTimeMillis();

        RequestInfo info = requests.compute(key, (k, existing) -> {

            if (existing == null ||
                    now - existing.windowStart > window.toMillis()) {

                return new RequestInfo(1, now);
            }

            existing.count++;
            return existing;
        });

        return info.count <= maxRequests;
    }

    private static class RequestInfo {
        int count;
        long windowStart;

        RequestInfo(int count, long windowStart) {
            this.count = count;
            this.windowStart = windowStart;
        }
    }


    private static final Logger log = LogManager.getLogger(RateLimitService.class);

//    // Atomic: increment key, set TTL only on first increment, return 1=allowed 0=blocked
//    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of(
//            """
//            local current = redis.call('INCR', KEYS[1])
//            if current == 1 then
//                redis.call('EXPIRE', KEYS[1], ARGV[2])
//            end
//            if current > tonumber(ARGV[1]) then
//                return 0
//            end
//            return 1
//            """,
//            Long.class
//    );
//
//    private final StringRedisTemplate stringRedisTemplate;
//
//    /**
//     * Returns true if the request is within the allowed rate, false if it should be blocked.
//     * Fails open on Redis errors so an outage never locks users out.
//     */
//    public boolean isAllowed(String key, int maxRequests, Duration window) {
//        try {
//            Long result = stringRedisTemplate.execute(
//                    RATE_LIMIT_SCRIPT,
//                    List.of(key),
//                    String.valueOf(maxRequests),
//                    String.valueOf(window.getSeconds())
//            );
//            return !Long.valueOf(0L).equals(result);
//        } catch (Exception e) {
//            log.error("Redis rate-limit check failed for key '{}' — failing open: {}", key, e.getMessage());
//            return true;
//        }

    //===================================== temp=================================================



}