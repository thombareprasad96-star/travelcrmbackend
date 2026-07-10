package com.crm.travelcrm.common.web;

import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders 401 as {@link com.crm.travelcrm.common.exception.ApiError} JSON.
 *
 * <p>Replaces {@code new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)}, which wrote <b>no body at
 * all</b>. The frontend reads {@code response.data.message}; against an empty body that is
 * {@code undefined}, so every expired session in the app surfaced as the generic
 * "Something went wrong."
 *
 * <p><b>The message is deliberately uniform and deliberately vague.</b> Not signed in, expired token,
 * disabled account, soft-deleted user, and a token invalidated by a {@code tokenVersion} bump all
 * produce this exact string. Distinguishing them would turn this endpoint into an account-enumeration
 * oracle — an attacker could tell "this email exists but is deactivated" from "this email does not
 * exist". The real reason is logged, never returned.
 *
 * <p>Shared by both realms: the staff chain and the traveler-portal chain.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Unauthenticated request to {}: {}", request.getRequestURI(), authException.toString());
        errorWriter.write(response, ErrorCode.UNAUTHENTICATED,
                "Your session has expired or you are not signed in. Please sign in again.");
    }
}