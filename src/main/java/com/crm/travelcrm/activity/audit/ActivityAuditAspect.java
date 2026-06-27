package com.crm.travelcrm.activity.audit;

import com.crm.travelcrm.activity.entity.ActivityAction;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Captures an {@link com.crm.travelcrm.activity.entity.ActivityLog} for every successful
 * <b>write</b> hitting a {@code @RestController} (POST/PUT/PATCH/DELETE), so the Activity Reports
 * have real data without each service publishing its own event.
 *
 * <p>Runs at the controller boundary (outside the service transaction), only {@code @AfterReturning}
 * (failed requests are not logged), and best-effort via {@code ActivityLogRecorder.safeRecord}.
 * Login/Logout are recorded explicitly in the auth flow, so {@code /api/auth/**} is skipped here;
 * the traveler portal is a separate realm and is skipped too.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityAuditAspect {

    private final ActivityLogRecorder recorder;

    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    public void restController() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PostMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PutMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PatchMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.DeleteMapping)")
    public void writeMapping() {}

    @AfterReturning("restController() && writeMapping()")
    public void recordWrite(JoinPoint joinPoint) {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return;
            }
            HttpServletRequest request = attrs.getRequest();
            String uri = request.getRequestURI();

            // Login/Logout recorded explicitly; portal is a separate auth realm.
            if (uri == null || uri.startsWith("/api/auth/") || uri.startsWith("/api/portal/")) {
                return;
            }

            // Only tenant users produce a tenant-scoped audit trail.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
                return;
            }

            ActivityAction action = resolveAction(request.getMethod(), uri);
            String handler = joinPoint.getSignature().getDeclaringType().getSimpleName()
                    + "." + joinPoint.getSignature().getName();
            String description = action + " — " + handler
                    + " [" + request.getMethod() + " " + uri + "]";

            recorder.safeRecord(
                    action,
                    description,
                    user.getId(),
                    user.getName(),
                    user.getEmail(),
                    ActivityLogRecorder.labelFor(user.getRole()),
                    user.getTenantId(),
                    ClientIp.resolve(request),
                    request.getHeader("User-Agent"));
        } catch (Exception ex) {
            // Never let auditing break a successful request.
            log.warn("Activity audit aspect failed: {}", ex.getMessage());
        }
    }

    private static ActivityAction resolveAction(String httpMethod, String uri) {
        if (uri.contains("/export")) {
            return ActivityAction.Export;
        }
        return switch (httpMethod) {
            case "POST"            -> ActivityAction.Create;
            case "PUT", "PATCH"    -> ActivityAction.Update;
            case "DELETE"          -> ActivityAction.Delete;
            default                -> ActivityAction.View;
        };
    }
}