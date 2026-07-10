package com.crm.travelcrm.common.context;

import org.apache.logging.log4j.ThreadContext;

import java.util.UUID;

/**
 * Per-request correlation id, stored in Log4j2's {@link ThreadContext} (a thread-local map) so the
 * logging pattern can print it via {@code %X{traceId}} without any code passing it around.
 *
 * <p>Lives beside {@link TenantContext} / {@link PlatformContext} because it has the identical
 * lifecycle: set once at the very top of the request, <b>always</b> cleared in a {@code finally}
 * (see {@code TraceIdFilter}) — otherwise a pooled worker thread would leak the previous request's
 * id into the next one's logs.
 *
 * <p>The id is always generated server-side. An inbound {@code X-Trace-Id} header is deliberately
 * ignored: it is client-controlled and would let a caller poison or collide log correlation.
 */
public final class TraceId {

    /** Key in the Log4j2 ThreadContext map; referenced as {@code %X{traceId}} in log4j2.xml. */
    public static final String KEY = "traceId";

    /** Response header echoing the id, so a support ticket can quote it even for an empty body. */
    public static final String HEADER = "X-Trace-Id";

    private TraceId() {}

    /** Generates a short id, stores it, and returns it. */
    public static String start() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ThreadContext.put(KEY, traceId);
        return traceId;
    }

    /** The current request's id, or {@code null} outside a request (e.g. a scheduler thread). */
    public static String current() {
        return ThreadContext.get(KEY);
    }

    /** MUST be called in a {@code finally} — prevents leaking the id across pooled threads. */
    public static void clear() {
        ThreadContext.remove(KEY);
    }
}