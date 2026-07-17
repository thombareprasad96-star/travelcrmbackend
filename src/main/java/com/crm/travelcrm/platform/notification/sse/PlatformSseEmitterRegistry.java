package com.crm.travelcrm.platform.notification.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Thread-safe registry of active console SSE connections per SuperAdmin. Supports multiple emitters
 * per SuperAdmin (e.g. several console tabs).
 *
 * <p><b>Why this is a separate bean and not a reuse of {@code SseEmitterRegistry}.</b> That registry
 * keys on a bare {@code Long} with no realm tag. {@code super_admins.id} and {@code users.id} come
 * from independent {@code IDENTITY} sequences, so SuperAdmin #1 and tenant user #1 are different
 * people with the same key — sharing the map would push a platform revenue alert into a tenant's
 * live browser tab. A composite key would also fix that, but only for as long as every future call
 * site remembers to build one. A separate map cannot be got wrong: there is no key that reaches the
 * other realm's emitters, because there is no shared map to reach into.
 */
@Slf4j
@Component
public class PlatformSseEmitterRegistry {

    private final Map<Long, Set<SseEmitter>> registry = new ConcurrentHashMap<>();

    public SseEmitter register(Long superAdminId) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        registry.computeIfAbsent(superAdminId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        Runnable cleanup = () -> deregister(superAdminId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("Console SSE error for super admin {}: {}", superAdminId, e.getMessage());
            cleanup.run();
        });

        log.debug("Console SSE emitter registered for super admin {}. Active emitters: {}",
                superAdminId, registry.getOrDefault(superAdminId, Set.of()).size());
        return emitter;
    }

    public void deregister(Long superAdminId, SseEmitter emitter) {
        // compute() so the "is it now empty?" check and the removal are one atomic step. Doing it as
        // a read-then-act (remove, then `if (isEmpty) registry.remove(key, set)`) races: a tab that
        // registers into the same set between those two lines is dropped along with the bucket, and
        // its emitter then never receives a push — a live connection that silently gets nothing.
        registry.computeIfPresent(superAdminId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    /** Push to all active emitters for one SuperAdmin. Dead emitters are pruned silently. */
    public void push(Long superAdminId, String eventName, Object data) {
        Set<SseEmitter> emitters = registry.getOrDefault(superAdminId, Set.of());
        if (emitters.isEmpty()) return;

        Set<SseEmitter> dead = new CopyOnWriteArraySet<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                // Exception, not IOException: send() on an already-completed or timed-out emitter
                // throws IllegalStateException, which is unchecked. Catching only IOException would
                // let one dead tab abort the loop and starve every LATER tab of the same SuperAdmin
                // — the failure the per-emitter isolation exists to prevent. Every throwable here
                // means the same thing: this emitter is unusable.
                dead.add(emitter);
            }
        }
        dead.forEach(e -> deregister(superAdminId, e));
    }

    public int activeConnections() {
        return registry.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Keeps idle console connections alive through proxies and load balancers. 25s mirrors the
     * tenant registry's interval — nginx's default proxy_read_timeout is 60s.
     */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        registry.forEach((superAdminId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    deregister(superAdminId, emitter);
                }
            }
        });
    }
}