package com.crm.travelcrm.common.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight in-memory fixed-window rate limiter.
 *
 * <p><b>Scope:</b> state is per application instance — behind more than one node each
 * counts independently. For a multi-instance deployment, back this with Redis
 * (atomic {@code INCR} + {@code EXPIRE}). A scheduled sweep evicts expired windows so the
 * map cannot grow unbounded from one-off / idle keys.
 */
@Service
public class RateLimitService {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean isAllowed(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.expiresAt) {
                return new Window(now + window.toMillis());   // start a fresh window
            }
            existing.count++;
            return existing;
        });
        return w.count <= maxRequests;
    }

    /** Evicts windows whose period has elapsed so idle keys don't accumulate forever. */
    @Scheduled(fixedRate = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        windows.values().removeIf(w -> now >= w.expiresAt);
    }

    private static final class Window {
        int count = 1;
        final long expiresAt;

        Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}