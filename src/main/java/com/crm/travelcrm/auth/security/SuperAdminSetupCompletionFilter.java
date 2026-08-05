package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.dev.DevSuperAdminLoginMode;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class SuperAdminSetupCompletionFilter extends OncePerRequestFilter {

    private static final String SUPER_ADMIN_API_PREFIX = "/api/super-admin/";

    private final SuperAdminRepository superAdminRepository;
    private final ApiErrorWriter errorWriter;
    private final DevSuperAdminLoginMode devLoginMode;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Local dev bypass: setup completion means "MFA enrolled and password changed", and that
        // can never become true on a login path that skips MFA — so leaving this gate armed would
        // lock the console out of everything except the setup endpoints. Non-prod only; see
        // DevSuperAdminLoginMode.
        if (devLoginMode.isEnabled()) {
            return true;
        }
        return !request.getRequestURI().startsWith(SUPER_ADMIN_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication != null ? authentication.getPrincipal() : null;

        if (principal instanceof SuperAdmin principalAdmin) {
            SuperAdmin admin = superAdminRepository.findByIdAndDeletedAtIsNull(principalAdmin.getId())
                    .orElse(null);
            if (admin != null && !admin.isSetupComplete() && !isSetupEndpoint(request)) {
                errorWriter.write(response, ErrorCode.PERMISSION_DENIED,
                        "Complete SuperAdmin setup before using the console.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isSetupEndpoint(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        String method = request.getMethod();
        return ("GET".equalsIgnoreCase(method) && "/api/super-admin/me".equals(uri))
                || ("POST".equalsIgnoreCase(method) && "/api/super-admin/me/change-password".equals(uri))
                || ("GET".equalsIgnoreCase(method) && "/api/super-admin/me/mfa".equals(uri));
    }
}
