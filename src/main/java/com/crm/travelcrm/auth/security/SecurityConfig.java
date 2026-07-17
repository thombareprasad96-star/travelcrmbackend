package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.common.ratelimit.RateLimitFilter;
import com.crm.travelcrm.common.web.ApiAccessDeniedHandler;
import com.crm.travelcrm.common.web.ApiAuthenticationEntryPoint;
import com.crm.travelcrm.platform.config.filter.MaintenanceModeFilter;
import com.crm.travelcrm.platform.entitlement.filter.ModuleAccessFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final SuperAdminDetailsService superAdminDetailsService;
    private final UserDetailsServiceImpl userDetailsService;
    private final RateLimitFilter rateLimitFilter;
    private final MaintenanceModeFilter maintenanceModeFilter;
    private final ModuleAccessFilter moduleAccessFilter;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;

    // Comma-separated allowed origins; override per environment (add the prod URL there).
    @org.springframework.beans.factory.annotation.Value(
            "${app.cors.allowed-origins:http://localhost:5173,http://localhost:5173}")
    private List<String> allowedOrigins;


    // ─── Authentication Providers ────────────────────────────────────────────

    @Bean
    public AuthenticationProvider superAdminAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(superAdminDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationProvider userAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    // ─── Security Filter Chain ───────────────────────────────────────────────

    @Bean
    @org.springframework.core.annotation.Order(2)   // after the portal chain (@Order(1)); catch-all
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE endpoints (e.g. Disha chat) complete on an async worker; when the
                        // container re-dispatches the finished response through the filter chain the
                        // SecurityContext is already cleared, so re-authorizing would 403 a response
                        // that's already committed. The original REQUEST dispatch was authorized, so
                        // permit the internal ASYNC/ERROR re-dispatches.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Payment-gateway webhooks (server-to-server; no user). Authenticity is the
                        // HMAC-SHA256 signature verified in the service, not the filter chain.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        // Lead-ingest provider HANDSHAKES (e.g. Meta's GET hub.challenge). The rule
                        // above is POST-qualified, so a GET falls to anyRequest().authenticated() and
                        // 401s the handshake.
                        //
                        // Scoped to /leads/** deliberately: do NOT relax the POST qualifier above to
                        // fix this — that would open the Razorpay webhook to GET. The ingest path
                        // authenticates on the opaque per-connection token in the URL, and rejects
                        // every unknown token with a 401 before touching the database.
                        .requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()
                        // Public quotation share links (capability URL by publicId) — read-only PDF
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // SSE streams: EventSource cannot set Authorization headers, so the JWT is
                        // passed as ?token= and validated in the controller. Each stream also checks
                        // the principal's realm there — permitAll means no @PreAuthorize gate runs,
                        // so "authenticated" alone would let either realm onto the other's feed.
                        .requestMatchers(HttpMethod.GET, "/api/notifications/stream").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/super-admin/notifications/stream").permitAll()
                        // Health probes for nginx / systemd / uptime monitoring, which cannot carry a
                        // JWT. Safe to expose: management.endpoint.health.show-details=never in prod,
                        // so an anonymous caller gets nothing but {"status":"UP"}. Only `health` is
                        // exposed at all — every other actuator endpoint stays off (see
                        // management.endpoints.web.exposure.include) AND unauthenticated here.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Maintenance gate runs AFTER auth so it can distinguish tenant vs platform traffic.
                .addFilterAfter(maintenanceModeFilter, JwtAuthFilter.class)
                // Module entitlement gate — 403s a tenant hitting a module its plan/flags exclude.
                .addFilterAfter(moduleAccessFilter, MaintenanceModeFilter.class)
                // Both render the standard ApiError envelope. The entry point previously wrote an
                // empty body and the access-denied handler forwarded to Boot's /error page, so the
                // frontend read `undefined` for message on every 401 and every filter-level 403.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler)
                )
                .build();
    }

    // ─── CORS ────────────────────────────────────────────────────────────────

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // ─── Shared Beans ────────────────────────────────────────────────────────

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Spring Boot auto-register band karo
        return registration;
    }

    @Bean
    public FilterRegistrationBean<MaintenanceModeFilter> maintenanceModeRegistration(MaintenanceModeFilter filter) {
        // Disable auto-registration so it runs ONLY inside the staff chain (never globally / on portal).
        FilterRegistrationBean<MaintenanceModeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ModuleAccessFilter> moduleAccessRegistration(ModuleAccessFilter filter) {
        // Disable auto-registration so it runs ONLY inside the staff chain (never globally / on portal).
        FilterRegistrationBean<ModuleAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

}