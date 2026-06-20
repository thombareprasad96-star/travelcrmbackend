package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.auth.api.TokenAuthenticator;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
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
    @PutMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponseDTO>> markRead(
            @PathVariable Long id) {

        NotificationResponseDTO dto = notificationService.markRead(id);
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
     * DELETE /api/notifications/{id}
     * Soft-delete one notification owned by the authenticated user.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        notificationService.delete(id);
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
    public SseEmitter stream(@RequestParam String token) {
        // Header-less auth: validate the token and populate SecurityContext + TenantContext
        // via the auth module's public facade (mirrors JwtAuthFilter for the SSE flow).
        if (!tokenAuthenticator.authenticateForCurrentThread(token)) {
            log.warn("SSE connection rejected: invalid or expired token");
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new SecurityException("Invalid token"));
            return rejected;
        }

        return notificationService.subscribe();
        // Note: TenantContext.clear() is NOT called here — the SSE response is async
        // and the thread is held open. The registry cleanup callbacks handle lifecycle.
    }
}
