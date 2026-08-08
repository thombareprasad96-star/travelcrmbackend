package com.crm.travelcrm.notification.infrastructure.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p><b>Connections are indexed by tenant as well as by user.</b> Both indexes are needed and
 * neither is derivable from the other at push time:
 *
 * <ul>
 *   <li>{@link #push} addresses ONE user — a notification about their booking.</li>
 *   <li>{@link #pushToTenant} addresses EVERY connected user of one tenant — a new lead is
 *       broadcast so anyone can claim it, and there is no recipient list to iterate.</li>
 * </ul>
 *
 * <p>Resolving the broadcast audience by querying {@code users} instead would push to people who
 * are not connected (wasted work on every lead) and, worse, would make the tenant boundary depend
 * on the caller passing the right ids. Here it is structural: a broadcast can only ever reach
 * emitters registered under that tenant, so a cross-tenant leak is not expressible.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    /** userId → that user's live emitters (one per open tab). */
    private final Map<Long, Set<SseEmitter>> byUser = new ConcurrentHashMap<>();

    /**
     * tenantId → the live emitters of every connected user in that tenant.
     *
     * <p>Holds the emitters directly rather than a set of userIds, so a broadcast is one map lookup
     * and never a second pass through {@link #byUser}. The two indexes are maintained together in
     * {@link #register} and {@link #deregister}; an emitter present in one but not the other is a
     * bug, which is why deregistration always touches both even when only one is known to hold it.
     */
    private final Map<Long, Set<SseEmitter>> byTenant = new ConcurrentHashMap<>();

    /**
     * emitter → the userId it belongs to.
     *
     * <p>{@link #byTenant} answers "who is connected to this tenant" but not "who is this
     * connection", and a filtered broadcast needs the second question: an audience is a set of
     * userIds, and without this map the only way back from an emitter to its user is a scan of
     * {@link #byUser}. Maintained in the same three places as the other two indexes.
     */
    private final Map<SseEmitter, Long> emitterOwner = new ConcurrentHashMap<>();

    /**
     * How long a connection may stay open before the server ends it and the client must reconnect.
     *
     * <p>This is an authorization control, not a resource one. A JWT is validated exactly once — at
     * connect time — so an emitter with no timeout keeps streaming to a user who has since been
     * deactivated, locked or had their permissions revoked, for as long as the tab stays open. The
     * reconnect re-runs {@code TokenAuthenticator}, which re-checks the principal, so a finite
     * lifetime is what makes revocation eventually take effect. 30 minutes by default: short enough
     * that a terminated user loses the feed promptly, long enough that reconnect churn is trivial.
     * The browser reconnects on its own, and the frontend rebuilds the stream with a fresh token
     * when the old one has expired, so this is invisible to the user.
     */
    @Value("${app.sse.emitter-timeout-ms:1800000}")
    private long emitterTimeoutMs;

    /**
     * Register a connection.
     *
     * @param tenantId the subscriber's tenant. May be null for a principal with no tenant, in which
     *                 case the connection simply receives no tenant broadcasts — it is NOT silently
     *                 filed under some default tenant, which would leak every broadcast to it.
     */
    public SseEmitter register(Long tenantId, Long userId) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        byUser.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(emitter);
        if (tenantId != null) {
            byTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArraySet<>()).add(emitter);
        }
        if (userId != null) {
            emitterOwner.put(emitter, userId);
        }

        Runnable cleanup = () -> deregister(tenantId, userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("SSE error for user {}: {}", userId, e.getMessage());
            cleanup.run();
        });

        log.debug("SSE emitter registered for user {} (tenant {}). Active emitters: {}",
                userId, tenantId, byUser.getOrDefault(userId, Set.of()).size());
        return emitter;
    }

    public void deregister(Long tenantId, Long userId, SseEmitter emitter) {
        removeFrom(byUser, userId, emitter);
        removeFrom(byTenant, tenantId, emitter);
        emitterOwner.remove(emitter);
    }

    /**
     * End every open connection of one user, now.
     *
     * <p>The companion to the emitter timeout: deactivation, a permission change or a forced logout
     * should not have to wait out the timeout window before the user's tabs stop receiving pushes.
     * {@code complete()} fires the emitter's own completion callback, which deregisters it, so both
     * indexes are cleaned by the existing path rather than a second one.
     */
    public int deregisterUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        Set<SseEmitter> emitters = byUser.getOrDefault(userId, Set.of());
        int closed = 0;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // Already dead. Prune directly — its completion callback will not fire again.
                dropEverywhere(emitter);
            }
            closed++;
        }
        return closed;
    }

    private void removeFrom(Map<Long, Set<SseEmitter>> index, Long key, SseEmitter emitter) {
        if (key == null) {
            return;
        }
        Set<SseEmitter> emitters = index.get(key);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                index.remove(key, emitters);
            }
        }
    }

    /**
     * Push data to all active emitters for the given user.
     * Dead emitters are removed silently (L principle: one dead tab ≠ others blocked).
     */
    public void push(Long userId, String eventName, Object data) {
        Set<SseEmitter> emitters = byUser.getOrDefault(userId, Set.of());
        if (emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            if (!trySend(emitter, eventName, data)) {
                // The tenant index is pruned too — a dead emitter left there would be retried on
                // every subsequent broadcast, and the tenant map would grow without bound.
                dropEverywhere(emitter);
            }
        }
    }

    /**
     * Push data to EVERY connected user of one tenant.
     *
     * <p>The audience is "whoever currently has a tab open", by design: this backs the new-lead
     * alert, and a lead that arrives while nobody is looking is caught by the persisted in-app
     * notification and the alert feed on next load, not by queueing pushes for absent users.
     *
     * @return how many emitters the event actually reached — logged by callers, and the honest
     *         answer to "did anyone see it", which a void return would hide
     */
    public int pushToTenant(Long tenantId, String eventName, Object data) {
        if (tenantId == null) {
            return 0;
        }
        Set<SseEmitter> emitters = byTenant.getOrDefault(tenantId, Set.of());
        if (emitters.isEmpty()) return 0;

        int delivered = 0;
        for (SseEmitter emitter : emitters) {
            if (trySend(emitter, eventName, data)) {
                delivered++;
            } else {
                dropEverywhere(emitter);
            }
        }
        return delivered;
    }

    /**
     * Send, reporting failure rather than throwing.
     *
     * <p>Catches {@link Exception}, not just {@link IOException}: a completed or errored emitter
     * throws {@code IllegalStateException} from {@code send()}, and letting that propagate would
     * abort a tenant broadcast partway through — one user's stale tab would silently cost everyone
     * else their alert.
     */
    private boolean trySend(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            log.debug("SSE send failed on event {}: {}", eventName, e.getMessage());
            return false;
        }
    }

    /** Remove a dead emitter from BOTH indexes without needing to know its keys. */
    private void dropEverywhere(SseEmitter emitter) {
        byUser.forEach((userId, set) -> set.remove(emitter));
        byUser.entrySet().removeIf(e -> e.getValue().isEmpty());
        byTenant.forEach((tenantId, set) -> set.remove(emitter));
        byTenant.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public int activeConnections() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    /** Live connections for one tenant — how many people a broadcast would currently reach. */
    public int activeConnections(Long tenantId) {
        return tenantId == null ? 0 : byTenant.getOrDefault(tenantId, Set.of()).size();
    }

    /**
     * Sends an SSE comment ({@code :keep-alive}) to every emitter every 25 seconds so proxies
     * and load balancers don't drop idle connections. Dead emitters are pruned on failure.
     */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        byUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    dropEverywhere(emitter);
                }
            }
        });
    }
}
