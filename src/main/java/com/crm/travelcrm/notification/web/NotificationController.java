package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.api.TokenAuthenticator;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * REST + SSE controller for the notification module.
 *
 * <p>All endpoints (except {@code /stream}) require a valid JWT in the
 * {@code Authorization} header — enforced by {@code JwtAuthFilter} and Spring
 * Security's {@code .anyRequest().authenticated()} rule.
 *
 * <p>The SSE endpoint ({@code GET /api/notifications/stream}) is permitted in
 * {@code SecurityConfig} because {@code EventSource} cannot set custom headers.
 * Token is passed as a query param and validated manually before the emitter is
 * registered.
 *
 * <p>Tenant isolation is automatic: every query runs through
 * {@link NotificationServiceImpl#currentUserId()} which reads from
 * {@code SecurityContext}, and the Hibernate {@code tenantFilter} on
 * {@code BaseTenantEntity} adds {@code WHERE tenant_id = ?} to every query.
 * No caller ever sends a {@code tenant_id} request parameter.
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService  notificationService;
    private final TokenAuthenticator   tokenAuthenticator;
    private final CurrentUserProvider  currentUserProvider;

    // ── Feed ──────────────────────────────────────────────────────────────────

    /**
     * GET /api/notifications?page=0&size=20
     * Returns paginated notification feed for the authenticated user.
     */
    @GetMapping
    public ResponseEntity<PagedApiResponse<NotificationResponseDTO>> getNotifications(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<NotificationResponseDTO> result = notificationService.getNotifications(page, size);
        return ResponseEntity.ok(
                PagedApiResponse.of("Notifications fetched", result.getContent(),
                        PaginationMeta.from(result, "createdAt", "desc")));
    }

    // ── Badge count ───────────────────────────────────────────────────────────

    /**
     * GET /api/notifications/unread-count
     * Returns {@code {"count": N}} — used by the bell badge in Navbar.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount() {
        long count = notificationService.getUnreadCount();
        return ResponseEntity.ok(
                ApiResponse.success("Unread count fetched", Map.of("count", count)));
    }

    // ── Mark single read ──────────────────────────────────────────────────────

    /**
     * PUT /api/notifications/{publicId}/read
     * Marks one notification as read. Returns 404 if not owned by the current user.
     */
    @PutMapping("/{publicId}/read")
    public ResponseEntity<ApiResponse<NotificationResponseDTO>> markRead(
            @PathVariable UUID publicId) {

        NotificationResponseDTO dto = notificationService.markRead(publicId);
        return ResponseEntity.ok(ApiResponse.success("Notification marked as read", dto));
    }

    // ── Mark all read ─────────────────────────────────────────────────────────

    /**
     * PUT /api/notifications/read-all (alias: /mark-all-read)
     * Bulk mark-all-read for the authenticated user.
     */
    @PutMapping({"/read-all", "/mark-all-read"})
    public ResponseEntity<ApiResponse<Void>> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.ok(ApiResponse.success("All notifications marked as read"));
    }

    // ── Delete (soft) ───────────────────────────────────────────────────────────

    /**
     * DELETE /api/notifications/{publicId}
     * Soft-delete one notification owned by the authenticated user.
     */
    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        notificationService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Notification deleted"));
    }

    // ── SSE stream ────────────────────────────────────────────────────────────

    /**
     * GET /api/notifications/stream?token={jwt}
     *
     * <p>EventSource cannot set Authorization headers, so the JWT is passed as
     * a query param. This endpoint is permitted in SecurityConfig (no filter
     * auth) and validates the token manually before registering the emitter.
     *
     * <p>Returns a long-lived SSE connection. The server pushes
     * {@code NotificationResponseDTO} JSON objects as events named
     * {@code "notification"}.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token, HttpServletResponse response) {
        // Header-less auth: validate the token and populate SecurityContext + TenantContext
        // via the auth module's public facade (mirrors JwtAuthFilter for the SSE flow).
        if (!tokenAuthenticator.authenticateForCurrentThread(token)) {
            // Reject cleanly with a 401. We must NOT complete the emitter with an exception:
            // that re-dispatches to the JSON @ExceptionHandler, which then can't negotiate a
            // text/event-stream response ("No acceptable representation") and blows up as a 500.
            // Setting the status and returning null lets Spring mark the request handled with an
            // empty body — no content negotiation, no stack trace. The client re-auths and reconnects.
            log.warn("SSE connection rejected: invalid or expired token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        // A SuperAdmin token authenticates *successfully* above — it is validly signed and its
        // principal loads — so it walks straight past the 401 door and into a tenant-only feed.
        // It must be turned away here, bodyless, for the same reason: subscribe() would throw, and
        // the JSON @ExceptionHandler cannot render into a text/event-stream response. A
        // BusinessException would NOT help — it serializes to JSON too. Platform sessions have
        // their own feed at /api/super-admin/notifications/stream.
        if (currentUserProvider.currentUserIdOrNull() == null) {
            log.warn("SSE connection rejected: non-tenant principal on the tenant notification stream");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        return notificationService.subscribe();
        // Note: TenantContext.clear() is NOT called here — ContextCleanupFilter clears it when this
        // (async) request releases the worker thread back to the pool, which is exactly when it
        // must stop carrying a tenant. Only the connection stays open, not the thread.
    }
}
