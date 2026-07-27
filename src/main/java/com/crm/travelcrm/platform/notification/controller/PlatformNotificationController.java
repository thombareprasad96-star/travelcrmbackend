package com.crm.travelcrm.platform.notification.controller;

import com.crm.travelcrm.auth.api.TokenAuthenticator;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.platform.notification.dto.PlatformNotificationResponseDTO;
import com.crm.travelcrm.platform.notification.service.PlatformNotificationService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Console notification feed for the platform SuperAdmin — the platform-realm counterpart to
 * {@code NotificationController}. Separate table, separate SSE registry, separate namespace; a
 * tenant token cannot reach these rows and a platform token cannot reach the tenant's.
 *
 * <p><b>{@code @PreAuthorize} is on the methods, not the type</b> (unlike {@code SuperAdminMeController}).
 * {@code /stream} must be {@code permitAll} at the filter chain — {@code EventSource} cannot send an
 * Authorization header — so it authenticates its own query-param token and cannot carry a type-level
 * role gate.
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/notifications")
@RequiredArgsConstructor
public class PlatformNotificationController {

    private final PlatformNotificationService platformNotificationService;
    private final TokenAuthenticator          tokenAuthenticator;

    // ── Feed ──────────────────────────────────────────────────────────────────

    /** GET /api/super-admin/notifications?page=0&size=20 */
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PagedApiResponse<PlatformNotificationResponseDTO>> getNotifications(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<PlatformNotificationResponseDTO> result =
                platformNotificationService.getNotifications(page, size);
        return ResponseEntity.ok(
                PagedApiResponse.of("Notifications fetched", result.getContent(),
                        PaginationMeta.from(result, "createdAt", "desc")));
    }

    // ── Badge count ───────────────────────────────────────────────────────────

    /** GET /api/super-admin/notifications/unread-count → {@code {"count": N}} */
    @GetMapping("/unread-count")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount() {
        long count = platformNotificationService.getUnreadCount();
        return ResponseEntity.ok(
                ApiResponse.success("Unread count fetched", Map.of("count", count)));
    }

    // ── Mark single read ──────────────────────────────────────────────────────

    @PutMapping("/{publicId}/read")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<PlatformNotificationResponseDTO>> markRead(
            @PathVariable UUID publicId) {

        PlatformNotificationResponseDTO dto = platformNotificationService.markRead(publicId);
        return ResponseEntity.ok(ApiResponse.success("Notification marked as read", dto));
    }

    // ── Mark all read ─────────────────────────────────────────────────────────

    @PutMapping({"/read-all", "/mark-all-read"})
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> markAllRead() {
        platformNotificationService.markAllRead();
        return ResponseEntity.ok(ApiResponse.success("All notifications marked as read"));
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        platformNotificationService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Notification deleted"));
    }

    // ── SSE stream ────────────────────────────────────────────────────────────

    /**
     * GET /api/super-admin/notifications/stream?token={jwt}
     *
     * <p>Permitted in {@code SecurityConfig} and authenticated here, because {@code EventSource}
     * cannot set headers. Both rejection paths below set a status and return {@code null} rather than
     * throwing: this endpoint declares {@code produces = text/event-stream}, and an exception would
     * be handed to the JSON {@code @ExceptionHandler}, which cannot negotiate a body this client will
     * accept — turning a clean 401/403 into a 500 with a stack trace. That is exactly the bug this
     * feature was reported for. Do not "improve" these into thrown exceptions.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token, HttpServletResponse response) {
        if (!tokenAuthenticator.authenticateForCurrentThread(token)) {
            log.warn("Console SSE connection rejected: invalid or expired token");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        // A tenant token authenticates perfectly well above — validly signed, principal loads. The
        // realm check is what keeps it out of the platform feed, and it must happen here because the
        // permitAll rule means no @PreAuthorize gate ran.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SuperAdmin superAdmin)) {
            log.warn("Console SSE connection rejected: non-platform principal on the platform stream");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        if (!superAdmin.isSetupComplete()) {
            log.warn("Console SSE connection rejected: SuperAdmin setup incomplete");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        return platformNotificationService.subscribe();
        // TenantContext/PlatformContext are cleared by ContextCleanupFilter when this async request
        // releases the worker thread — not here. The connection stays open; the thread does not.
    }
}
