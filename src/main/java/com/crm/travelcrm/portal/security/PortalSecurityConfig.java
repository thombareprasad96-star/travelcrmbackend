package com.crm.travelcrm.portal.security;

import com.crm.travelcrm.common.ratelimit.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dedicated {@link SecurityFilterChain} for the traveler portal — ordered BEFORE the staff chain
 * and matched only to {@code /api/portal/**}. Because Spring Security dispatches each request to the
 * first matching chain, portal requests run ONLY this chain (staff {@code JwtAuthFilter} never sees
 * them), and everything else falls through to the staff chain. Combined with the distinct portal
 * signing key, a staff token can never authenticate here and a traveler token can never authenticate
 * on the staff chain.
 *
 * <p>Only {@code /api/portal/auth/**} (OTP request/verify) is public; every other portal endpoint
 * requires an authenticated traveler. The auth endpoints are additionally IP rate-limited
 * ({@link RateLimitFilter}) for brute-force protection, on top of the per-account OTP attempt cap.</p>
 */
@Configuration
@RequiredArgsConstructor
public class PortalSecurityConfig {

    private final TravelerJwtAuthFilter travelerJwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain portalFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/portal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/portal/**").permitAll()
                        // OTP request/verify — the only unauthenticated portal endpoints.
                        .requestMatchers("/api/portal/auth/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(travelerJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) ->
                                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied"))
                )
                .build();
    }

    /**
     * Stop Spring Boot from also registering the portal filter as a global servlet filter (it must
     * run ONLY inside the portal chain). Mirrors how {@code SecurityConfig} disables the rate-limit
     * filter's auto-registration.
     */
    @Bean
    public FilterRegistrationBean<TravelerJwtAuthFilter> travelerFilterRegistration(
            TravelerJwtAuthFilter filter) {
        FilterRegistrationBean<TravelerJwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
