package com.crm.travelcrm.notification.infrastructure.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe registry of active SSE connections per user.
 * Supports multiple emitters per user (e.g. multiple browser tabs).
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<Long, Set<SseEmitter>> registry = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        registry.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> deregister(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE error for user {}: {}", userId, e.getMessage());
            cleanup.run();
        });

        log.debug("SSE emitter registered for user {}. Active emitters: {}",
                userId, registry.getOrDefault(userId, Set.of()).size());
        return emitter;
    }

    public void deregister(Long userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = registry.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                registry.remove(userId, emitters);
            }
        }
    }

    /**
     * Push data to all active emitters for the given user.
     * Dead emitters are removed silently (L principle: one dead tab ≠ others blocked).
     */
    public void push(Long userId, String eventName, Object data) {
        Set<SseEmitter> emitters = registry.getOrDefault(userId, Set.of());
        if (emitters.isEmpty()) return;

        Set<SseEmitter> dead = new CopyOnWriteArraySet<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        dead.forEach(e -> deregister(userId, e));
    }

    public int activeConnections() {
        return registry.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Sends an SSE comment ({@code :keep-alive}) to every emitter every 25 seconds so proxies
     * and load balancers don't drop idle connections. Dead emitters are pruned on failure.
     */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        registry.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    deregister(userId, emitter);
                }
            }
        });
    }
}