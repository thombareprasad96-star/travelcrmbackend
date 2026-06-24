package com.crm.travelcrm.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client IP from a request. Prefers the first hop of {@code X-Forwarded-For}
 * (set by a reverse proxy / load balancer), then {@code X-Real-IP}, falling back to the socket
 * peer. Used for weblink-view analytics classification (not a security control).
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        if (request == null) return null;

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // "client, proxy1, proxy2" — the first hop is the original client.
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}