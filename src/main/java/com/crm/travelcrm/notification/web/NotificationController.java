package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.security.JwtUtil;
import com.crm.travelcrm.auth.security.UserDetailsServiceImpl;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
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

    private final NotificationService    notificationService;
    private final JwtUtil                jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;

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
     * PUT /api/notifications/mark-all-read
     * Bulk mark-all-read for the authenticated user.
     */
    @PutMapping("/mark-all-read")
    public ResponseEntity<ApiResponse<Void>> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.ok(ApiResponse.success("All notifications marked as read"));
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
        // Manual token validation — mirrors JwtAuthFilter but without the filter chain
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("SSE connection rejected: invalid or expired token");
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new SecurityException("Invalid token"));
            return rejected;
        }

        String email    = jwtUtil.extractEmail(token);
        Long   tenantId = jwtUtil.extractTenantId(token);

        try {
            User user = (User) userDetailsService.loadUserByEmailAndTenantId(email, tenantId);

            // Populate SecurityContext so NotificationServiceImpl.currentUserId() works
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            TenantContext.setTenantId(tenantId);

            return notificationService.subscribe();

        } catch (Exception e) {
            log.warn("SSE connection rejected for email={}: {}", email, e.getMessage());
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(e);
            return rejected;
        }
        // Note: TenantContext.clear() is NOT called here — the SSE response is async
        // and the thread is held open. The registry cleanup callbacks handle lifecycle.
    }
}
